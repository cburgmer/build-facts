(ns buildviz.concourse.builds-test
  (:require [buildviz.concourse.builds :as sut]
            [cheshire.core :as j]
            [clj-http.fake :as fake]
            [clj-time
             [coerce :as tc]
             [core :as t]]
            [clojure.test :refer :all]))

(def beginning-of-2016 (t/date-time 2016 1 1))

(defn- successful-json-response [body]
  (fn [_] {:status 200
           :body (j/generate-string body)}))

(defn- a-job [team-name pipeline-name job-name]
  {:team_name team-name
   :pipeline_name pipeline-name
   :name job-name})

(defn- all-jobs [& jobs]
  [["http://concourse:8000/api/v1/jobs"
    (successful-json-response jobs)]])

(defn- some-builds-up-to [up-to-id team-name pipeline-name job-name & builds]
  [[(format "http://concourse:8000/api/v1/teams/%s/pipelines/%s/jobs/%s/builds?to=%s"
            team-name
            pipeline-name
            job-name
            up-to-id)
    (successful-json-response (map #(assoc % :pipeline_name pipeline-name :job_name job-name) builds))]])

(defn- some-builds [team-name pipeline-name job-name & builds]
  (apply some-builds-up-to (concat ["" team-name pipeline-name job-name] builds)))

(defn- valid-session []
  [["http://concourse:8000/api/v1/user"
    (successful-json-response {})]])


(defn- serve-up [& routes]
  (->> routes
       (mapcat identity) ; flatten once
       (into {})))

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
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"}
                                   beginning-of-2016)
             '({:job-name "my-pipeline my-job"
                :build-id "42"
                :outcome "pass"
                :start 1451642400000
                :end 1451642401000})))))

  (testing "should sync a failing build"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "failed"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"}
                                   beginning-of-2016)
             '({:job-name "my-pipeline my-job"
                :build-id "42"
                :outcome "fail"
                :start 1451642400000
                :end 1451642401000})))))

  (testing "should handle aborted build without start time"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:name "42"
                                                                :status "aborted"
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"}
                                   beginning-of-2016)
             '({:job-name "my-pipeline my-job"
                :build-id "42"
                :outcome "fail"
                :start 1451642401000
                :end 1451642401000})))))

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
                                                                      :end_time (unix-time-in-s 2016 1 1 2 0 1)}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"}
                                   beginning-of-2016)
             '({:job-name "my-pipeline my-job"
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
                :end 1451613601000})))))

  (testing "should stop syncing after given date"
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
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}
                                                               {:id 2
                                                                :name "41"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2015 12 31 10 0 0)
                                                                :end_time (unix-time-in-s 2015 12 31 10 0 1)}
                                                               {:id 1
                                                                :name "40"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2015 12 30 10 0 0)
                                                                :end_time (unix-time-in-s 2015 12 30 10 0 1)}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"}
                                   beginning-of-2016)
             '({:job-name "my-pipeline my-job"
                :build-id "43"
                :outcome "pass"
                :start 1454328000000
                :end 1454328001000}
               {:job-name "my-pipeline my-job"
                :build-id "42"
                :outcome "pass"
                :start 1451642400000
                :end 1451642401000})))))

  (testing "should not confuse date of multiple jobs"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job")
                                                            (a-job "my-team" "my-pipeline" "another-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}
                                                               {:id 2
                                                                :name "41"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2015 12 31 10 0 0)
                                                                :end_time (unix-time-in-s 2015 12 31 10 0 1)})
                                                  (some-builds "my-team" "my-pipeline" "another-job"
                                                               {:id 3
                                                                :name "10"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}
                                                               {:id 1
                                                                :name "9"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2015 12 31 10 0 0)
                                                                :end_time (unix-time-in-s 2015 12 31 10 0 1)}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"}
                                   beginning-of-2016)
             '({:job-name "my-pipeline my-job"
                :build-id "42"
                :outcome "pass"
                :start 1451642400000
                :end 1451642401000}
               {:job-name "my-pipeline another-job"
                :build-id "10"
                :outcome "pass"
                :start 1451642400000
                :end 1451642401000}))))))
