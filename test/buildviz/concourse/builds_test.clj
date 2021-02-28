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

(defn- some-builds [team-name pipeline-name job-name & builds]
  [[(format "http://concourse:8000/api/v1/teams/%s/pipelines/%s/jobs/%s/builds"
            team-name
            pipeline-name
            job-name)
    (successful-json-response (map #(assoc % :pipeline_name pipeline-name :job_name job-name) builds))]])

(defn- valid-session []
  [["http://concourse:8000/api/v1/user"
    (successful-json-response {})]])


(defn- serve-up [& routes]
  (->> routes
       (mapcat identity) ; flatten once
       (into {})))



(deftest test-concourse-builds
  (testing "should sync a build"
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
                        :end 9876000}}))))))
