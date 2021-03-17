(ns build-facts.sync-test
  (:require [build-facts.sync :as sut]
            [clj-time.core :as t]
            [cheshire.core :as j]
            [clj-time
             [core :as t]
             [coerce :as tc]
             [local :as l]]
            [clojure.java.io :as io]
            [clojure.test :refer :all]))

(def beginning-of-2016 (t/date-time 2016 1 1))

(defmacro with-no-err [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#]
       ~@body)))

(defn- create-tmp-dir [prefix] ; http://stackoverflow.com/questions/617414/create-a-temporary-directory-in-java
  (let [tmp-file (java.io.File/createTempFile prefix ".tmp")]
    (.delete tmp-file)
    (.mkdirs tmp-file)
    (.getPath tmp-file)))

(defn- unix-time [& params]
  (tc/to-long (apply t/date-time params)))

(def one-month-ago (t/minus (.withTimeAtStartOfDay (l/local-now)) (t/months 1)))
(def three-months-ago (t/minus (.withTimeAtStartOfDay (l/local-now)) (t/months 3)))

(deftest test-sync
  (testing "sends the unix epoch in seconds as event timestamp for Splunk"
    (let [tmp-dir (create-tmp-dir "tmp")]
      (is (= {:time 123456
              :source "build-facts"
              :event {:jobName "fake-job"
                      :start 123456789}}
             (j/parse-string (with-out-str
                               (with-no-err
                                 (sut/sync-builds {:base-url 'some-url'
                                                   :user-sync-start-time beginning-of-2016
                                                   :splunkFormat? true
                                                   :state-file-path (format "%s/state.json" tmp-dir)}
                                                  (fn [_] '({:job-name "fake-job"
                                                             :start 123456789})))))
                             true))))))

(deftest test-sync-v2
  (testing "syncs a build"
    (is (= '({:jobName "fake-job"
              :buildId "21"
              :outcome "pass"
              :start 1451642400000})
           (->> (with-out-str
                  (with-no-err
                    (sut/sync-builds-v2 {:base-url 'some-url'
                                         :user-sync-start-time beginning-of-2016}
                                        (fn [_] [["fake-job"
                                                  [{:job-name "fake-job"
                                                    :build-id "21"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 1 10 0 0)}]]]))))
                clojure.string/split-lines
                (map #(j/parse-string % true))))))

  (testing "in absence of a sync start time will fallback to two months of data"
    (is (= '("21")
           (->> (with-out-str
                  (with-no-err
                    (sut/sync-builds-v2 {:base-url 'some-url'}
                                        (fn [_] [["fake-job"
                                                  [{:job-name "fake-job"
                                                    :build-id "21"
                                                    :outcome "pass"
                                                    :start (tc/to-long one-month-ago)}
                                                   {:job-name "fake-job"
                                                    :build-id "20"
                                                    :outcome "pass"
                                                    :start (tc/to-long three-months-ago)}]]]))))
                clojure.string/split-lines
                (map #(j/parse-string % true))
                (map :buildId)))))

  (testing "syncs from oldest build to newest build with a known outcome so we can come back later and resume once build is done"
    (is (= '({:jobName "fake-job"
              :buildId "20"
              :outcome "fail"
              :start 1451638800000}
             {:jobName "fake-job"
              :buildId "21"
              :outcome "pass"
              :start 1451642400000})
           (->> (with-out-str
                  (with-no-err
                    (sut/sync-builds-v2 {:base-url 'some-url'
                                         :user-sync-start-time beginning-of-2016}
                                        (fn [_] [["fake-job"
                                                  [{:job-name "fake-job"
                                                    :build-id "23"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 3 10 0 0)}
                                                   {:job-name "fake-job"
                                                    :build-id "22"
                                                    :outcome "running"
                                                    :start (unix-time 2016 1 2 10 0 0)}
                                                   {:job-name "fake-job"
                                                    :build-id "21"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 1 10 0 0)}
                                                   {:job-name "fake-job"
                                                    :build-id "20"
                                                    :outcome "fail"
                                                    :start (unix-time 2016 1 1 9 0 0)}]]]))))
                clojure.string/split-lines
                (map #(j/parse-string % true))))))

  (testing "syncs builds from two jobs"
    (is (= '({:jobName "fake-job"
              :buildId "21"
              :outcome "pass"
              :start 1451642400000}
             {:jobName "fake-job"
              :buildId "22"
              :outcome "pass"
              :start 1451728800000}
             {:jobName "another-job"
              :buildId "42"
              :outcome "pass"
              :start 1451725200000})
           (->> (with-out-str
                  (with-no-err
                    (sut/sync-builds-v2 {:base-url 'some-url'
                                         :user-sync-start-time beginning-of-2016}
                                        (fn [_] [["fake-job"
                                                  [{:job-name "fake-job"
                                                    :build-id "22"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 2 10 0 0)}
                                                   {:job-name "fake-job"
                                                    :build-id "21"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 1 10 0 0)}]]
                                                 ["another-job"
                                                  [{:job-name "another-job"
                                                    :build-id "42"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 2 9 0 0)}]]]))))
                clojure.string/split-lines
                (map #(j/parse-string % true))))))

  (testing "syncs starting from given user start time"
    (is (= '({:jobName "fake-job"
              :buildId "21"
              :outcome "pass"
              :start 1451642400000}
             {:jobName "fake-job"
              :buildId "22"
              :outcome "pass"
              :start 1451728800000})
           (->> (with-out-str
                  (with-no-err
                    (sut/sync-builds-v2 {:base-url 'some-url
                                         :user-sync-start-time beginning-of-2016}
                                        (fn [_] [["fake-job"
                                                  [{:job-name "fake-job"
                                                    :build-id "22"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 2 10 0 0)}
                                                   {:job-name "fake-job"
                                                    :build-id "21"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 1 10 0 0)}
                                                   {:job-name "fake-job"
                                                    :build-id "20"
                                                    :outcome "pass"
                                                    :start (unix-time 2015 12 31 10 0 0)}]]]))))
                clojure.string/split-lines
                (map #(j/parse-string % true))))))

  (testing "saves sync state of all jobs to state file"
    (let [state-file (format "%s/state.json" (create-tmp-dir "tmp"))]
      (with-out-str
                  (with-no-err
                    (sut/sync-builds-v2 {:base-url 'some-url
                                         :user-sync-start-time beginning-of-2016
                                         :state-file-path state-file}
                                        (fn [_] [["fake-job"
                                                  [{:job-name "fake-job"
                                                    :build-id "23"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 3 10 0 0)}
                                                   {:job-name "fake-job"
                                                    :build-id "22"
                                                    :outcome "running"}
                                                   {:job-name "fake-job"
                                                    :build-id "21"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 1 10 0 0)}]]
                                                 ["another-job"
                                                  [{:job-name "another-job"
                                                    :build-id "42"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 2 9 0 0)}]]]))))
      (is (= {"jobs" {"fake-job" {"lastStart" 1451642400000}
                      "another-job" {"lastStart" 1451725200000}}}
             (j/parse-string (slurp state-file))))))

  (testing "resumes sync from last state saved in state file"
    (let [state-file (format "%s/state.json" (create-tmp-dir "tmp"))]
      (spit state-file (j/generate-string {"jobs" {"fake-job" {"lastStart" 1451642400000}
                                                   "another-job" {"lastStart" 1451725200000}}}))
      (is (= '({:jobName "fake-job"
                :buildId "22"
                :outcome "pass"
                :start 1451728800000}
               {:jobName "fake-job"
                :buildId "23"
                :outcome "pass"
                :start 1451815200000}
               {:jobName "another-job"
                :buildId "43"
                :outcome "fail"
                :start 1451732400000})
             (->> (with-out-str
                    (with-no-err
                      (sut/sync-builds-v2 {:base-url 'some-url
                                           :user-sync-start-time beginning-of-2016
                                           :state-file-path state-file}
                                          (fn [_] [["fake-job"
                                                    [{:job-name "fake-job"
                                                      :build-id "23"
                                                      :outcome "pass"
                                                      :start (unix-time 2016 1 3 10 0 0)}
                                                     {:job-name "fake-job"
                                                      :build-id "22"
                                                      :outcome "pass"
                                                      :start (unix-time 2016 1 2 10 0 0)}
                                                     {:job-name "fake-job"
                                                      :build-id "21"
                                                      :outcome "pass"
                                                      :start (unix-time 2016 1 1 10 0 0)}]]
                                                   ["another-job"
                                                    [{:job-name "another-job"
                                                      :build-id "43"
                                                      :outcome "fail"
                                                      :start (unix-time 2016 1 2 11 0 0)}
                                                     {:job-name "another-job"
                                                      :build-id "42"
                                                      :outcome "pass"
                                                      :start (unix-time 2016 1 2 9 0 0)}]]]))))
                  clojure.string/split-lines
                  (map #(j/parse-string % true)))))))

  (testing "updates the state file"
    (let [state-file (format "%s/state.json" (create-tmp-dir "tmp"))]
      (spit state-file (j/generate-string {"jobs" {"fake-job" {"lastStart" 1451642400000}
                                                   "another-job" {"lastStart" 1451725200000}}}))
      (with-out-str
        (with-no-err
          (sut/sync-builds-v2 {:base-url 'some-url
                               :user-sync-start-time beginning-of-2016
                               :state-file-path state-file}
                              (fn [_] [["fake-job"
                                        [{:job-name "fake-job"
                                          :build-id "22"
                                          :outcome "pass"
                                          :start (unix-time 2016 1 2 10 0 0)}
                                         {:job-name "fake-job"
                                          :build-id "21"
                                          :outcome "pass"
                                          :start (unix-time 2016 1 1 10 0 0)}]]
                                       ["another-job"
                                        [{:job-name "another-job"
                                          :build-id "43"
                                          :outcome "fail"
                                          :start (unix-time 2016 1 2 11 0 0)}
                                         {:job-name "another-job"
                                          :build-id "42"
                                          :outcome "pass"
                                          :start (unix-time 2016 1 2 9 0 0)}]]]))))
      (is (= {"jobs" {"fake-job" {"lastStart" 1451728800000}
                      "another-job" {"lastStart" 1451732400000}}}
             (j/parse-string (slurp state-file))))))

  (testing "should pick up new jobs not in state file (falling back to sync start time)"
    (let [state-file (format "%s/state.json" (create-tmp-dir "tmp"))]
      (spit state-file (j/generate-string {"jobs" {"fake-job" {"lastStart" 1580511600000}}}))
      (is (= '({:jobName "new-job"
                :buildId "43"
                :outcome "fail"
                :start 1451732400000})
             (->> (with-out-str
                    (with-no-err
                      (sut/sync-builds-v2 {:base-url 'some-url
                                           :user-sync-start-time beginning-of-2016
                                           :state-file-path state-file}
                                          (fn [_] [["fake-job"
                                                    [{:job-name "fake-job"
                                                      :build-id "21"
                                                      :outcome "pass"
                                                      :start (unix-time 2020 1 1 0 0 0)}]]
                                                   ["new-job"
                                                    [{:job-name "new-job"
                                                      :build-id "43"
                                                      :outcome "fail"
                                                      :start (unix-time 2016 1 2 11 0 0)}]]]))))
                  clojure.string/split-lines
                  (map #(j/parse-string % true)))))))

  (testing "should store an empty jobs list if nothing was synced on first call"
    (let [state-file (format "%s/state.json" (create-tmp-dir "tmp"))]
      (with-out-str
        (with-no-err
          (sut/sync-builds-v2 {:base-url 'some-url
                               :user-sync-start-time beginning-of-2016
                               :state-file-path state-file}
                              (fn [_] []))))

      (is (= {"jobs" {}}
             (j/parse-string (slurp state-file))))))

  (testing "should not override a job's state if nothing new was synced for it while others were"
    (let [state-file (format "%s/state.json" (create-tmp-dir "tmp"))]
      (spit state-file (j/generate-string {"jobs" {"fake-job" {"lastStart" 1580511600000}}}))
      (with-out-str
        (with-no-err
          (sut/sync-builds-v2 {:base-url 'some-url
                               :user-sync-start-time beginning-of-2016
                               :state-file-path state-file}
                              (fn [_] [["fake-job"
                                        [{:job-name "fake-job"
                                          :build-id "21"
                                          :outcome "pass"
                                          :start (unix-time 2020 1 1 0 0 0)}]]
                                       ["new-job"
                                        [{:job-name "new-job"
                                          :build-id "43"
                                          :outcome "fail"
                                          :start (unix-time 2016 1 2 11 0 0)}]]]))))

      (is (= {"jobs" {"fake-job" {"lastStart" 1580511600000}
                      "new-job" {"lastStart" 1451732400000}}}
             (j/parse-string (slurp state-file))))))

  (testing "should optionally sync in Splunk HEC format"
    (is (= '({:time 1451642400
              :source "build-facts"
              :event {:jobName "fake-job"
                      :buildId "21"
                      :outcome "pass"
                      :start 1451642400000}})
           (->> (with-out-str
                  (with-no-err
                    (sut/sync-builds-v2 {:base-url 'some-url'
                                         :user-sync-start-time beginning-of-2016
                                         :splunkFormat? true}
                                        (fn [_] [["fake-job"
                                                  [{:job-name "fake-job"
                                                    :build-id "21"
                                                    :outcome "pass"
                                                    :start (unix-time 2016 1 1 10 0 0)}]]]))))
                clojure.string/split-lines
                (map #(j/parse-string % true))))))

  (testing "should optionally store results"
    (let [tmp-dir (create-tmp-dir "tmp")]
      (with-out-str
        (with-no-err
          (sut/sync-builds-v2 {:base-url 'some-url'
                               :user-sync-start-time beginning-of-2016
                               :output tmp-dir}
                              (fn [_] [["fake-job"
                                        [{:job-name "fake-job"
                                          :build-id "21"
                                          :outcome "pass"
                                          :start (unix-time 2016 1 1 10 0 0)}]]]))))
      (is (= '("fake-job")
             (->> (.listFiles (io/file tmp-dir))
                  (map #(.getName %)))))
      (is (= '("21.json")
             (->> (.listFiles (io/file tmp-dir "fake-job"))
                  (map #(.getName %)))))
      (is (= {:jobName "fake-job"
              :buildId "21"
              :outcome "pass"
              :start 1451642400000}
             (j/parse-string (slurp (io/file tmp-dir "fake-job" "21.json"))
                             true))))))
