(ns buildviz.concourse.builds-test
  (:require [buildviz.concourse.builds :as sut]
            [cheshire.core :as j]
            [clj-http.fake :as fake]
            [clojure.test :refer :all]))

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



(deftest test-concourse-builds
  (testing "should sync a successful build"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:name "42"
                                                                :status "succeeded"
                                                                :start_time 1234
                                                                :end_time 9876}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"})
             '({:job-name "my-pipeline my-job"
                :build-id "42"
                :build {:outcome "pass"
                        :start 1234000
                        :end 9876000}})))))

  (testing "should sync a failing build"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:name "42"
                                                                :status "failed"
                                                                :start_time 1234
                                                                :end_time 9876}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"})
             '({:job-name "my-pipeline my-job"
                :build-id "42"
                :build {:outcome "fail"
                        :start 1234000
                        :end 9876000}})))))

  (testing "should handle aborted build without start time"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:name "42"
                                                                :status "succeeded"
                                                                :end_time 9876}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"})
             '({:job-name "my-pipeline my-job"
                :build-id "42"
                :build {:outcome "pass"
                        :start 9876000
                        :end 9876000}})))))

  (testing "should handle pagination for long build histories"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 5
                                                                :name "43"
                                                                :status "succeeded"
                                                                :start_time 9875
                                                                :end_time 9876}
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time 8764
                                                                :end_time 8765})
                                                  (some-builds-up-to 4
                                                                     "my-team" "my-pipeline" "my-job"
                                                                     {:id 4
                                                                      :name "42"
                                                                      :status "succeeded"
                                                                      :start_time 8764
                                                                      :end_time 8765}
                                                                     {:id 2
                                                                      :name "41"
                                                                      :status "succeeded"
                                                                      :start_time 4320
                                                                      :end_time 4321})
                                                  (some-builds-up-to 2
                                                                     "my-team" "my-pipeline" "my-job"
                                                                     {:id 2
                                                                      :name "41"
                                                                      :status "succeeded"
                                                                      :start_time 4320
                                                                      :end_time 4321}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"})
             '({:job-name "my-pipeline my-job"
                :build-id "43"
                :build {:outcome "pass"
                        :start 9875000
                        :end 9876000}}
               {:job-name "my-pipeline my-job"
                :build-id "42"
                :build {:outcome "pass"
                        :start 8764000
                        :end 8765000}}
               {:job-name "my-pipeline my-job"
                :build-id "41"
                :build {:outcome "pass"
                        :start 4320000
                        :end 4321000}}))))))
