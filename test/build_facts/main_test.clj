(ns build-facts.main-test
  (:require [build-facts.main :as sut]
            [cheshire.core :as j]
            [clj-yaml.core :as yaml]
            [clj-http.fake :as fake]
            [clj-time
             [coerce :as tc]
             [core :as t]]
            [clojure.java.io :as io]
            [clojure.string :as string]
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
   (successful-json-response (map #(assoc % :pipeline_name pipeline-name :job_name job-name) builds))])

(defn- some-builds [team-name pipeline-name job-name & builds]
  (apply some-builds-up-to (concat ["" team-name pipeline-name job-name] builds)))

(defn- some-resources [build-id & inputs]
  [(format "http://concourse:8000/api/v1/builds/%s/resources" build-id)
   (successful-json-response {:inputs inputs})])

(defn- some-plan [build-id & tasks]
  [(format "http://concourse:8000/api/v1/builds/%s/plan" build-id)
   (successful-json-response {:plan {:do tasks}})])

(defn- some-events [build-id & event-data]
  (let [events (concat (map-indexed (fn [idx data]
                                      (let [data-json (j/generate-string {:data data})]
                                        (format "id: %s\nevent: event\ndata: %s" idx data-json)))
                                    event-data)
                       [(format "id: %s\nevent: end\ndata" (count event-data))
                        ""])]
    [(format "http://concourse:8000/api/v1/builds/%s/events" build-id)
     (fn [_] {:status 200
              :body (string/join "\n\n" events)})]))

(defn- valid-session []
  ["http://concourse:8000/api/v1/user"
   (successful-json-response {})])


(defn- serve-up [& routes]
  (into {} routes))

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
                                                          :team "my-team"
                                                          :token {:type "bearer"
                                                                  :value "dontcare"}}}}))
     ~@body))

(defmacro with-no-err [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#]
       ~@body)))


(deftest test-main
  (testing "should run a sync and stream results"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4)
                                                  (some-events 4))
      (let [tmp-dir (create-tmp-dir "data")]
        (with-fake-flyrc tmp-dir
          (let [output (with-out-str
                         (with-no-err
                           (sut/-main "concourse" "mock-target" "--from" "2016-01-01")))]
            (is (= {:jobName "my-pipeline my-job"
                    :buildId "42"
                    :outcome "pass"
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
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4)
                                                  (some-events 4))
      (let [data-dir (create-tmp-dir "data")]
        (with-fake-flyrc data-dir
          (with-out-str
            (sut/-main "concourse" "mock-target" "--from" "2016-01-01" "--output" data-dir)))

        (is (= ["42.json"]
               (->> (.listFiles (io/file data-dir "my-pipeline my-job"))
                    (map #(.getName %)))))
        (is (= {:jobName "my-pipeline my-job"
                :buildId "42"
                :outcome "pass"
                :start 1451642400000
                :end 1451642401000}
               (j/parse-string (slurp (io/file data-dir
                                               "my-pipeline my-job"
                                               "42.json"))
                               true))))))

  (testing "should use state to resume from last sync"
    (let [tmp-dir (create-tmp-dir "tmp")]
      (with-fake-flyrc tmp-dir
        (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                      (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                      (some-builds "my-team" "my-pipeline" "my-job"
                                                                   {:id 4
                                                                    :name "42"
                                                                    :status "succeeded"
                                                                    :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                    :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                      (some-resources 4)
                                                      (some-plan 4)
                                                      (some-events 4))
          (with-out-str
            (with-no-err
              (sut/-main "concourse"
                         "mock-target"
                         "--from" "2016-01-01"
                         "--state" (.getPath (io/file tmp-dir "state.json"))))))
        (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                      (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                      (some-builds "my-team" "my-pipeline" "my-job"
                                                                   {:id 5
                                                                    :name "43"
                                                                    :status "succeeded"
                                                                    :start_time (unix-time-in-s 2016 1 1 11 0 0)
                                                                    :end_time (unix-time-in-s 2016 1 1 11 0 1)}
                                                                   {:id 4
                                                                    :name "42"
                                                                    :status "succeeded"
                                                                    :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                    :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                      (some-resources 5)
                                                      (some-resources 4)
                                                      (some-plan 5)
                                                      (some-plan 4)
                                                      (some-events 5)
                                                      (some-events 4))
          (= '({:jobName "my-pipeline my-job"
                :buildId "43"
                :outcome "pass"
                :start 1451642400000
                :end 1451642401000})
             (->> (with-out-str
                    (with-no-err
                      (sut/-main "concourse"
                                 "mock-target"
                                 "--state" (.getPath (io/file tmp-dir "state.json")))))
                  (clojure.string/split-lines)
                  (map #(j/parse-string % true))))))))

  (testing "should run a sync and stream results in Splunk format"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4)
                                                  (some-events 4))
      (let [tmp-dir (create-tmp-dir "data")]
        (with-fake-flyrc tmp-dir
          (let [output (with-out-str
                         (with-no-err
                           (sut/-main "concourse" "mock-target" "--from" "2016-01-01" "--splunk")))]
            (is (= {:time 1451642400
                    :source "build-facts"
                    :event {:jobName "my-pipeline my-job"
                            :buildId "42"
                            :outcome "pass"
                            :start 1451642400000
                            :end 1451642401000}}
                   (j/parse-string output
                                   true)))))))))
