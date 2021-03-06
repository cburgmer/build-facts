(ns buildviz.concourse.sync-test
  (:require [buildviz.concourse.sync :as sut]
            [cheshire.core :as j]
            [clj-yaml.core :as yaml]
            [clj-http.fake :as fake]
            [clj-time
             [coerce :as tc]
             [core :as t]]
            [clojure.java.io :as io]
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

(defn- create-tmp-dir [prefix] ; http://stackoverflow.com/questions/617414/create-a-temporary-directory-in-java
  (let [tmp-file (java.io.File/createTempFile prefix ".tmp")]
    (.delete tmp-file)
    (.mkdirs tmp-file)
    (.getPath tmp-file)))


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
      (let [data-dir (create-tmp-dir "data")]
        (with-redefs [slurp (constantly (yaml/generate-string
                                         {:targets {:mock-target {:api "http://concourse:8000"
                                                                  :token {:type "bearer"
                                                                          :value "dontcare"}}}}))]
          (with-out-str
            (sut/-main "mock-target" "--from" "2016-01-01" "--output" data-dir)))

        (is (= ["42.json"]
               (->> (.listFiles (io/file data-dir "my-pipeline my-job"))
                    (map #(.getName %)))))
        (is (= {:outcome "pass"
                :start 1451642400000
                :end 1451642401000}
               (j/parse-string (slurp (io/file data-dir
                                               "my-pipeline my-job"
                                               "42.json"))
                               true))))))

  (testing "should store the last synced build time"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}))
      (let [tmp-dir (create-tmp-dir "tmp")]
        (with-redefs [slurp (constantly (yaml/generate-string
                                         {:targets {:mock-target {:api "http://concourse:8000"
                                                                  :token {:type "bearer"
                                                                          :value "dontcare"}}}}))]
          (with-out-str
            (sut/-main "mock-target"
                       "--from" "2016-01-01"
                       "--output" tmp-dir
                       "--state" (.getPath (io/file tmp-dir "state.json")))))

        (is (= {:lastBuildStart 1451642400000}
               (j/parse-string (slurp (.getPath (io/file tmp-dir "state.json")))
                               true))))))

  (testing "should not store the last synced build time if nothing was synched"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}))
      (let [tmp-dir (create-tmp-dir "tmp")]
        (with-redefs [slurp (constantly (yaml/generate-string
                                         {:targets {:mock-target {:api "http://concourse:8000"
                                                                  :token {:type "bearer"
                                                                          :value "dontcare"}}}}))]
          (with-out-str
            (sut/-main "mock-target"
                       "--from" "2020-01-01"
                       "--output" tmp-dir
                       "--state" (.getPath (io/file tmp-dir "state.json")))))

        (is (not (.exists (io/file tmp-dir "state.json"))))))))
