(ns build-facts.concourse.builds-test
  (:require [build-facts.concourse.builds :as sut]
            [cheshire.core :as j]
            [clj-http.fake :as fake]
            [clj-time
             [coerce :as tc]
             [core :as t]]
            [clojure.test :refer :all]))

(defn- successful-json-response [body]
  (fn [_] {:status 200
           :body (j/generate-string body)}))

(defn- a-job [team-name pipeline-name job-name]
  {:team_name team-name
   :pipeline_name pipeline-name
   :name job-name})

(defn- all-jobs [& jobs]
  ["http://concourse:8000/api/v1/jobs"
   (successful-json-response jobs)])

(defn- some-builds-up-to [up-to-id team-name pipeline-name job-name & builds]
  [(format "http://concourse:8000/api/v1/teams/%s/pipelines/%s/jobs/%s/builds?to=%s"
           team-name
           pipeline-name
           job-name
           up-to-id)
   (successful-json-response (map #(assoc %
                                          :pipeline_name pipeline-name
                                          :job_name job-name)
                                  builds))])

(defn- some-builds [team-name pipeline-name job-name & builds]
  (apply some-builds-up-to (concat ["" team-name pipeline-name job-name] builds)))

(defn- some-resources [build-id & inputs]
  [(format "http://concourse:8000/api/v1/builds/%s/resources" build-id)
   (successful-json-response {:inputs inputs})])

(defn- valid-session []
  ["http://concourse:8000/api/v1/user"
   (successful-json-response {})])


(defn- serve-up [& routes]
  (into {} routes))

(defn- unix-time-in-s [& params]
  (/ (tc/to-long (apply t/date-time params))
     1000))


(deftest test-concourse-builds
  (testing "should sync a successful build"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "pass"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "should sync a failing build"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "failed"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "fail"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "should handle a started build"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "started"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "ongoing"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "should handle aborted build without start time"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "aborted"
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "fail"
                  :start 1451642401000
                  :end 1451642401000}]])))))

  (testing "should handle errored build"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "errored"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "fail"
                  :start 1451642400000,
                  :end 1451642401000}]])))))

  (testing "should not sync builds from a different team"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job")
                                                            (a-job "another-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-builds "another-team" "my-pipeline" "my-job"
                                                               {:id 10
                                                                :name "43"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-resources 10))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "pass"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "should handle pagination for long build histories"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 5
                                                                :name "43"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 2 1 12 0 0)
                                                                :end_time (unix-time-in-s 2016 2 1 12 0 1)}
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-builds-up-to 4
                                                                     "my-team" "my-pipeline" "my-job"
                                                                     {:id 4
                                                                      :name "42"
                                                                      :status "succeeded"
                                                                      :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                      :end_time (unix-time-in-s 2016 1 1 10 0 1)}
                                                                     {:id 2
                                                                      :name "41"
                                                                      :status "succeeded"
                                                                      :start_time (unix-time-in-s 2016 1 1 2 0 0)
                                                                      :end_time (unix-time-in-s 2016 1 1 2 0 1)})
                                                  (some-builds-up-to 2
                                                                     "my-team" "my-pipeline" "my-job"
                                                                     {:id 2
                                                                      :name "41"
                                                                      :status "succeeded"
                                                                      :start_time (unix-time-in-s 2016 1 1 2 0 0)
                                                                      :end_time (unix-time-in-s 2016 1 1 2 0 1)})
                                                  (some-resources 5)
                                                  (some-resources 4)
                                                  (some-resources 2))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "43"
                  :outcome "pass"
                  :start 1454328000000
                  :end 1454328001000}
                 {:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "pass"
                  :start 1451642400000
                  :end 1451642401000}
                 {:job-name "my-pipeline my-job"
                  :build-id "41"
                  :outcome "pass"
                  :start 1451613600000
                  :end 1451613601000}]])))))

  (testing "should resolve builds lazily"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}
                                                               {:id 3
                                                                :name "41"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 9 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 9 0 1)})
                                                  (some-resources 4))
      (let [[[_ builds]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                :bearer-token "fake-token"
                                                :team-name "my-team"})]
        (first builds)))) ; should not error due to route for build #3 not defined

  (testing "should handle multiple jobs"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job")
                                                            (a-job "my-team" "my-pipeline" "another-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-builds "my-team" "my-pipeline" "another-job"
                                                               {:id 10
                                                                :name "10"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-resources 10))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "pass"
                  :start 1451642400000
                  :end 1451642401000}]]
               ["my-pipeline another-job"
                [{:job-name "my-pipeline another-job"
                  :build-id "10"
                  :outcome "pass"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "shpuld expose inputs"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4
                                                                  {:name "git"
                                                                   :version {:ref "abcd1234"}}
                                                                  {:name "version"
                                                                   :version {:number "1113.0.0"}}))
      (let [[[_, [build]]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (= [{:revision "abcd1234" :source-id "git[ref]"}
                {:revision "1113.0.0" :source-id "version[number]"}]
               (:inputs build)))))))
