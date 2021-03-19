(ns build-facts.teamcity.builds-test
  (:require [build-facts.teamcity.builds :as sut]
            [build-facts.util.url :as url]
            [cheshire.core :as j]
            [clj-http.fake :as fake]
            [clj-time.core :as t]
            [clojure.test :refer :all]))

(defn- successful-json-response [body]
  (fn [_] {:status 200
           :body (j/generate-string body)}))

(defn- a-job [id project-name job-name]
  {:id id
   :projectName project-name
   :name job-name})

(defn- a-project [id & jobs]
  [(format "http://teamcity:8000/httpAuth/app/rest/projects/%s" id)
   (successful-json-response {:buildTypes {:buildType jobs}})])

(defn- a-project-with-sub-projects [id & sub-projects]
  [(format "http://teamcity:8000/httpAuth/app/rest/projects/%s" id)
   (successful-json-response {:projects {:project sub-projects}
                              :buildTypes {:buildType []}})])

(defn- a-sub-project [id]
  {:id id})

(defn- a-job-with-builds [job-id & builds]
  [(format "http://teamcity:8000/httpAuth/app/rest/buildTypes/id:%s/builds/?locator=count:100,start:0&fields=build(id,number,status,startDate,finishDate,state,revisions%%28revision%%28version%%2Cvcs-root-instance%%29%%29,snapshot-dependencies%%28build%%28number%%2CbuildType%%28name%%2CprojectName%%29%%29%%29,triggered)"
           job-id)
   (successful-json-response {:build (map #(merge {:revisions []
                                                   :status "SUCCESS"
                                                   :state "finished"}
                                                  %) builds)})])

(defn- some-test-occurences [job-id build-id & tests]
  [(format "http://teamcity:8000/httpAuth/app/rest/testOccurrences?locator=count:10000,start:0,build:(id:%s)"
           build-id)
   (successful-json-response {:testOccurrence tests})])

(defn- no-test-occurences [job-id build-id & tests]
  [(format "http://teamcity:8000/httpAuth/app/rest/testOccurrences?locator=count:10000,start:0,build:(id:%s)"
           build-id)
   (successful-json-response {:testOccurrence []})])

(defn- serve-up [& routes]
  (into {} routes))


(deftest test-teamcity-builds
  (testing "should sync a build"
    (fake/with-fake-routes-in-isolation (serve-up (a-project "the_project" (a-job "theJobId" "theProject" "theJob #1"))
                                                  (a-job-with-builds "theJobId" {:id 42
                                                                                 :number "2"
                                                                                 :status "SUCCESS"
                                                                                 :startDate "20160410T041049+0000"
                                                                                 :finishDate "20160410T041100+0000"})
                                                  (no-test-occurences "theJobId" 42))
      (is (= [["theProject theJob #1"
               [{:job-name "theProject theJob #1"
                 :build-id "2"
                 :start 1460261449000
                 :end 1460261460000
                 :outcome "pass"}]]]
             (sut/teamcity-builds {:base-url (url/url "http://teamcity:8000")
                                   :projects ["the_project"]})))))

  (testing "should sync multiple jobs"
    (fake/with-fake-routes-in-isolation (serve-up (a-project "the_project"
                                                             (a-job "jobId1" "theProject" "job1")
                                                             (a-job "jobId2" "theProject" "job2"))
                                                  (a-job-with-builds "jobId1"
                                                                     {:id 12
                                                                      :number "11"
                                                                      :startDate "20160410T000300+0000"
                                                                      :finishDate "20160410T000400+0000"}
                                                                     {:id 10
                                                                      :number "10"
                                                                      :startDate "20160410T000000+0000"
                                                                      :finishDate "20160410T000100+0000"})
                                                  (no-test-occurences "jobId1" 12)
                                                  (no-test-occurences "jobId1" 10)
                                                  (a-job-with-builds "jobId2" {:id 20
                                                                               :number "42"
                                                                               :startDate "20160410T000100+0000"
                                                                               :finishDate "20160410T000200+0000"})
                                                  (no-test-occurences "jobId2" 20))
      (is (= [["theProject job1"
               [{:job-name "theProject job1"
                 :build-id "11"
                 :start 1460246580000
                 :end 1460246640000
                 :outcome "pass"}
                {:job-name "theProject job1"
                 :build-id "10"
                 :start 1460246400000
                 :end 1460246460000
                 :outcome "pass"}]]
              ["theProject job2"
               [{:job-name "theProject job2"
                 :build-id "42"
                 :start 1460246460000
                 :end 1460246520000
                 :outcome "pass"}]]]
             (sut/teamcity-builds {:base-url (url/url "http://teamcity:8000")
                                   :projects ["the_project"]})))))

  (testing "should sync test results"
    (fake/with-fake-routes-in-isolation (serve-up (a-project "the_project"
                                                             (a-job "jobId1" "theProject" "job1"))
                                                  (a-job-with-builds "jobId1"
                                                                     {:id 10
                                                                      :number "10"
                                                                      :startDate "20160410T000000+0000"
                                                                      :finishDate "20160410T000100+0000"})
                                                  (some-test-occurences "jobId1"
                                                                        10
                                                                        {:name "suite: class.the test"
                                                                         :status "SUCCESS"
                                                                         :duration 42}))
      (let [[[_ [build]]] (sut/teamcity-builds {:base-url (url/url "http://teamcity:8000")
                                                :projects ["the_project"]})]
        (is (= [{:name "suite"
                 :children [{:name "the test"
                             :classname "class"
                             :status "pass"
                             :runtime 42}]}]
               (:test-results build))))))

  (testing "should sync a sub project"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-project-with-sub-projects "the_project"
                                             (a-sub-project "the_sub_project"))
                (a-project "the_sub_project"
                           (a-job "jobId1" "The Sub Project" "job1"))
                (a-job-with-builds "jobId1"
                                   {:id 10
                                    :number "10"
                                    :status "SUCCESS"
                                    :startDate "20160410T041049+0000"
                                    :finishDate "20160410T041100+0000"})
                (no-test-occurences "jobId1" 10))
      (is (= [["The Sub Project job1"
               [{:job-name "The Sub Project job1"
                 :build-id "10"
                 :outcome "pass"
                 :start 1460261449000
                 :end 1460261460000}]]]
             (sut/teamcity-builds {:base-url (url/url "http://teamcity:8000")
                                   :projects ["the_project"]}))))))
