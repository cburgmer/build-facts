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

(defmacro with-properties [property-map & body] ; https://gist.github.com/eigenhombre/a1e7d98efddf5ebfdd90efdcebaffff6
  `(let [pm# ~property-map
         props# (into {} (for [[k# v#] pm#]
                           [k# (System/getProperty k#)]))]
     (doseq [k# (keys pm#)]
       (System/setProperty k# (get pm# k#)))
     (try
       ~@body
       (finally
         (doseq [k# (keys pm#)]
           (if-not (get props# k#)
             (System/clearProperty k#)
             (System/setProperty k# (get props# k#))))))))

(defmacro with-fake-flyrc [target-dir & body]
  `(with-properties {"user.home" ~target-dir}
     (spit (io/file ~target-dir ".flyrc")
           (yaml/generate-string {:targets {:mock-target {:api "http://concourse:8000"
                                                          :token {:type "bearer"
                                                                  :value "dontcare"}}}}))
     ~@body))

(defmacro with-no-err [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#]
       ~@body)))


(deftest test-concourse-sync
  (testing "should run a sync and stream results"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}))
      (let [tmp-dir (create-tmp-dir "data")]
        (with-fake-flyrc tmp-dir
          (let [output (with-out-str
                         (with-no-err
                           (sut/-main "mock-target" "--from" "2016-01-01")))]
            (is (= {:outcome "pass"
                    :start 1451642400000
                    :end 1451642401000}
                   (j/parse-string output
                                   true))))))))

  (testing "should run a sync and store results"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}))
      (let [data-dir (create-tmp-dir "data")]
        (with-fake-flyrc data-dir
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
        (with-fake-flyrc tmp-dir
          (with-out-str
            (sut/-main "mock-target"
                       "--from" "2016-01-01"
                       "--output" tmp-dir
                       "--state" (.getPath (io/file tmp-dir "state.json")))))

        (is (= {:lastBuildStart 1451642400000}
               (j/parse-string (slurp (.getPath (io/file tmp-dir "state.json")))
                               true))))))

  (testing "should store the last synced build time with multiple jobs"
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
                                                               {:id 2
                                                                :name "10"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 9 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 9 0 1)}))
      (let [tmp-dir (create-tmp-dir "tmp")]
        (with-fake-flyrc tmp-dir
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
        (with-fake-flyrc tmp-dir
          (with-out-str
            (sut/-main "mock-target"
                       "--from" "2020-01-01"
                       "--output" tmp-dir
                       "--state" (.getPath (io/file tmp-dir "state.json")))))

        (is (not (.exists (io/file tmp-dir "state.json")))))))

  (testing "should resume from last synced job using state"
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
                                                                :end_time (unix-time-in-s 2015 12 31 10 0 1)}))
      (let [tmp-dir (create-tmp-dir "tmp")
            state-file (io/file tmp-dir "state.json")]
        (with-fake-flyrc tmp-dir
          (spit state-file
                (j/generate-string {:lastBuildStart 1451642400000}))
          (with-out-str
            (sut/-main "mock-target"
                       "--output" tmp-dir
                       "--state" (.getPath state-file))))

        (is (= ["43.json"]
               (->> (.listFiles (io/file tmp-dir "my-pipeline my-job"))
                    (map #(.getName %)))))))))
