(ns buildviz.jenkins.builds-test
  (:require [buildviz.jenkins.builds :as sut]
            [buildviz.util.url :as url]
            [cheshire.core :as j]
            [clj-http.fake :as fake]
            [clj-time
             [coerce :as tc]
             [core :as t]]
            [clojure.java.io :as io]
            [clojure.test :refer :all]))

(defn- successful-json-response [body]
  (fn [_] {:status 200
           :body (j/generate-string body)}))

(defn- a-job [name]
  {:name name})

(defn- a-view [& jobs]
  ["http://jenkins:4321/api/json"
   (successful-json-response {:jobs jobs})])

(defn- a-job-with-builds [job-name & builds]
  [(format "http://jenkins:4321/job/%s/api/json?tree=allBuilds%%5Bnumber,timestamp,duration,result,actions%%5BlastBuiltRevision%%5BSHA1%%5D,remoteUrls,parameters%%5Bname,value%%5D,causes%%5BupstreamProject,upstreamBuild,userId%%5D%%5D%%5D%%7B0,10%%7D"
           job-name)
   (successful-json-response {:allBuilds builds})])

(defn- a-test-case [className name duration status]
  {:className className :name name :duration duration :status status})

(defn- a-test-suite [suite-name & test-cases]
  {:name suite-name :cases test-cases})

(defn- some-test-report [job-name build-number & suites]
  [(format "http://jenkins:4321/job/%s/%s/testReport/api/json" job-name build-number)
   (successful-json-response {:suites suites})])

(defn- no-test-report [job-name build-number]
  [(format "http://jenkins:4321/job/%s/%s/testReport/api/json" job-name build-number)
   (fn [_] {:status 404 :body ""})])

(defn- serve-up [& routes]
  (into {} routes))

(def beginning-of-2016 (t/date-time 2016 1 1))


(deftest test-sync-jobs
  (testing "should handle no jobs"
    (fake/with-fake-routes-in-isolation (serve-up (a-view))
      (is (empty? (sut/jenkins-builds {:base-url (url/url "http://jenkins:4321")} beginning-of-2016)))))

  (testing "should handle no builds"
    (fake/with-fake-routes-in-isolation (serve-up (a-view (a-job "some_job"))
                                                  (a-job-with-builds "some_job"))
      (is (empty? (sut/jenkins-builds {:base-url (url/url "http://jenkins:4321")} beginning-of-2016)))))

  (testing "should sync a simple build"
    (fake/with-fake-routes-in-isolation (serve-up (a-view (a-job "some_job"))
                                                  (a-job-with-builds "some_job" {:number 21
                                                                                 :timestamp 1493201298062
                                                                                 :duration 10200
                                                                                 :result "SUCCESS"})
                                                  (no-test-report "some_job" 21))
      (is (= '({:job-name "some_job"
                :build-id "21"
                :start 1493201298062
                :end 1493201308262
                :outcome "pass"})
             (sut/jenkins-builds {:base-url (url/url "http://jenkins:4321")} beginning-of-2016)))))

  (testing "should include inputs and reference to build it was triggered by"
    (fake/with-fake-routes-in-isolation (serve-up (a-view (a-job "some_job"))
                                                  (a-job-with-builds "some_job" {:number 21
                                                                                 :timestamp 1493201298062
                                                                                 :duration 10200
                                                                                 :result "SUCCESS"
                                                                                 :actions [{:lastBuiltRevision {:SHA1 "234567890"}
                                                                                            :remoteUrls ["some-url"]}
                                                                                           {:parameters '({:name "the-name"
                                                                                                           :value "some-value"})}
                                                                                           {:causes [{:upstreamProject "the_upstream"
                                                                                                      :upstreamBuild "33"}]}]})
                                                  (no-test-report "some_job" 21))
      (is (= '({:job-name "some_job"
                :build-id "21"
                :start 1493201298062
                :end 1493201308262
                :outcome "pass"
                :inputs ({:revision "234567890" :source-id "some-url"}
                         {:revision "some-value" :source-id "the-name"})
                :triggered-by ({:job-name "the_upstream", :build-id "33"})})
             (sut/jenkins-builds {:base-url (url/url "http://jenkins:4321")} beginning-of-2016)))))

  (testing "should omit build trigger if triggered by user due to temporal disconnect"
    (fake/with-fake-routes-in-isolation (serve-up (a-view (a-job "some_job"))
                                                  (a-job-with-builds "some_job" {:number 21
                                                                                 :timestamp 1493201298062
                                                                                 :duration 10200
                                                                                 :result "SUCCESS"
                                                                                 :actions [{:causes [{:upstreamProject "the_upstream"
                                                                                                      :upstreamBuild "33"}]}
                                                                                           {:causes [{:userId "the_user"}]}]})
                                                  (no-test-report "some_job" 21))
      (is (nil? (-> (sut/jenkins-builds {:base-url (url/url "http://jenkins:4321")} beginning-of-2016)
                    first
                    :triggered-by)))))

  (testing "should include test results"
    (fake/with-fake-routes-in-isolation (serve-up (a-view (a-job "some_job"))
                                                  (a-job-with-builds "some_job" {:number 21
                                                                                 :timestamp 1493201298062
                                                                                 :duration 10200
                                                                                 :result "SUCCESS"
                                                                                 :actions [{:causes [{:upstreamProject "the_upstream"
                                                                                                      :upstreamBuild "33"}]}
                                                                                           {:causes [{:userId "the_user"}]}]})
                                                  (some-test-report "some_job"
                                                                    21
                                                                    (a-test-suite "my-suite"
                                                                                  (a-test-case "my-class" "my-name" 0.042 "PASSED"))))
      (is (= '({:name "my-suite"
                :children ({:classname "my-class"
                            :name "my-name"
                            :runtime 42
                            :status "pass"})})
             (-> (sut/jenkins-builds {:base-url (url/url "http://jenkins:4321")} beginning-of-2016)
                 first
                 :test-results))))))
