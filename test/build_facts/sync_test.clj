(ns build-facts.sync-test
  (:require [build-facts.sync :as sut]
            [clj-time.core :as t]
            [cheshire.core :as j]
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


(deftest test-sync
  (testing "sends the unix epoch in seconds as event timestamp for Splunk"
    (let [tmp-dir (create-tmp-dir "tmp")]
      (is (= {:time 123456
              :source "build-data"
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
