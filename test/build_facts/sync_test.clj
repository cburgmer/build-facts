(ns build-facts.sync-test
  (:require [build-facts.sync :as sut]
            [clj-time.core :as t]
            [cheshire.core :as j]
            [clj-time
             [core :as t]
             [coerce :as tc]]
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

(deftest test-sync
  (testing "sends the unix epoch in seconds as event timestamp for Splunk"
    (let [tmp-dir (create-tmp-dir "tmp")]
      (is (= {:time 123456
              :source "build-facts"
              :event {:jobName "fake-job"
                      :end 123456789}}
             (j/parse-string (with-out-str
                               (with-no-err
                                 (sut/sync-builds {:base-url 'some-url'
                                                   :user-sync-start-time beginning-of-2016
                                                   :splunkFormat? true
                                                   :state-file-path (format "%s/state.json" tmp-dir)}
                                                  (fn [_] '({:job-name "fake-job"
                                                             :end 123456789})))))
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
                                        (fn [_] [[{:job-name "fake-job"
                                                   :build-id "21"
                                                   :outcome "pass"
                                                   :start (unix-time 2016 1 1 10 0 0)}]]))))
                clojure.string/split-lines
                (map #(j/parse-string % true))))))

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
                                        (fn [_] [[{:job-name "fake-job"
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
                                                   :start (unix-time 2016 1 1 9 0 0)}]]))))
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
                                        (fn [_] [[{:job-name "fake-job"
                                                   :build-id "22"
                                                   :outcome "pass"
                                                   :start (unix-time 2016 1 2 10 0 0)}
                                                  {:job-name "fake-job"
                                                   :build-id "21"
                                                   :outcome "pass"
                                                   :start (unix-time 2016 1 1 10 0 0)}]
                                                 [{:job-name "another-job"
                                                   :build-id "42"
                                                   :outcome "pass"
                                                   :start (unix-time 2016 1 2 9 0 0)}]]))))
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
                                        (fn [_] [[{:job-name "fake-job"
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
                                                   :start (unix-time 2015 12 31 10 0 0)}]]))))
                clojure.string/split-lines
                (map #(j/parse-string % true))))))

  (testing "saves sync state of all jobs to state file"
    (let [state-file (format "%s/state.json" (create-tmp-dir "tmp"))]
      (with-out-str
                  (with-no-err
                    (sut/sync-builds-v2 {:base-url 'some-url
                                         :user-sync-start-time beginning-of-2016
                                         :state-file-path state-file}
                                        (fn [_] [[{:job-name "fake-job"
                                                   :build-id "23"
                                                   :outcome "pass"
                                                   :start (unix-time 2016 1 3 10 0 0)}
                                                  {:job-name "fake-job"
                                                   :build-id "22"
                                                   :outcome "running"}
                                                  {:job-name "fake-job"
                                                   :build-id "21"
                                                   :outcome "pass"
                                                   :start (unix-time 2016 1 1 10 0 0)}]
                                                 [{:job-name "another-job"
                                                   :build-id "42"
                                                   :outcome "pass"
                                                   :start (unix-time 2016 1 2 9 0 0)}]]))))
      (is (= {"jobs" {"fake-job" {"lastStart" 1451642400000}
                      "another-job" {"lastStart" 1451725200000}}}
             (j/parse-string (slurp state-file)))))))
