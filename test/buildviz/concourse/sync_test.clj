(ns buildviz.concourse.sync-test
  (:require [buildviz.concourse.sync :as sut]
            [cheshire.core :as j]
            [clj-yaml.core :as yaml]
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


(deftest test-concourse-sync
  (testing "should run a sync"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}))
      (with-redefs [slurp (constantly (yaml/generate-string
                                       {:targets {:mock-target {:api "http://concourse:8000"
                                                                :token {:type "bearer"
                                                                        :value "dontcare"}}}}))]
        (with-out-str
          (sut/-main "mock-target" "--from" "2016-01-01"))))))
