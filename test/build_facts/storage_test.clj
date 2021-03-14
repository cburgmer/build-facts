(ns build-facts.storage-test
  (:require [build-facts.storage :as storage]
            [clojure.java.io :as io]
            [clojure
             [test :refer :all]]))

(defn- create-tmp-dir [prefix] ; http://stackoverflow.com/questions/617414/create-a-temporary-directory-in-java
  (let [tmp-file (java.io.File/createTempFile prefix ".tmp")]
    (.delete tmp-file)
    (.getPath tmp-file)))

(deftest test-store-build!
  (testing "should persist json"
    (let [data-dir (create-tmp-dir "build-facts-data")]
      (storage/store-build! data-dir "aJob" "aBuild" {:start 42})
      (is (= "{\"start\":42}"
             (slurp (io/file data-dir "aJob/aBuild.json"))))))

  (testing "should transform to camel-case keys"
    (let [data-dir (create-tmp-dir "build-facts-data")]
      (storage/store-build! data-dir "aJob" "aBuild" {:start 42 :inputs [{:revision "abcd" :source-id 42}]})
      (is (= "{\"start\":42,\"inputs\":[{\"revision\":\"abcd\",\"sourceId\":42}]}"
             (slurp (io/file data-dir "aJob/aBuild.json"))))))

  (testing "should safely encode illegal filenames"
    (let [data-dir (create-tmp-dir "build-facts-data")]
      (storage/store-build! data-dir "aJob\n" ":" {:start 42})
      (is (= "{\"start\":42}"
             (slurp (io/file data-dir "aJob%0a/%3a.json")))))))
