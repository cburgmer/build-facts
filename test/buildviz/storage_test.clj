(ns buildviz.storage-test
  (:require [buildviz.storage :as storage]
            [clojure.java.io :as io]
            [clojure
             [test :refer :all]]))

(defn- create-tmp-dir [prefix] ; http://stackoverflow.com/questions/617414/create-a-temporary-directory-in-java
  (let [tmp-file (java.io.File/createTempFile prefix ".tmp")]
    (.delete tmp-file)
    (.getPath tmp-file)))

(deftest test-store-build!
  (testing "should persist json"
    (let [data-dir (create-tmp-dir "buildviz-data")]
      (storage/store-build! data-dir "aJob" "aBuild" {:start 42})
      (is (= "{\"start\":42}"
             (slurp (io/file data-dir "aJob/aBuild.json"))))))

  (testing "should transform to camel-case keys"
    (let [data-dir (create-tmp-dir "buildviz-data")]
      (storage/store-build! data-dir "aJob" "aBuild" {:start 42 :inputs [{:revision "abcd" :source-id 42}]})
      (is (= "{\"start\":42,\"inputs\":[{\"revision\":\"abcd\",\"sourceId\":42}]}"
             (slurp (io/file data-dir "aJob/aBuild.json"))))))

  (testing "should safely encode illegal filenames"
    (let [data-dir (create-tmp-dir "buildviz-data")]
      (storage/store-build! data-dir "aJob\n" ":" {:start 42})
      (is (= "{\"start\":42}"
             (slurp (io/file data-dir "aJob%0a/%3a.json")))))))


(deftest test-load-builds
  (testing "should return nil when no builds exist"
    (let [data-dir (create-tmp-dir "test-storage")]
      (is (empty? (storage/load-builds data-dir)))))

  (testing "should return the one and only build"
    (let [data-dir (create-tmp-dir "test-storage")]
      (.mkdirs (io/file data-dir "someJob"))
      (spit (io/file data-dir "someJob/someBuild.json") "{\"start\": 42}")
      (is (= [{:start 42}]
             (storage/load-builds data-dir)))))

  (testing "should return two builds of the same job"
    (let [data-dir (create-tmp-dir "test-storage")]
      (.mkdirs (io/file data-dir "someJob"))
      (spit (io/file data-dir "someJob/someBuild.json") "{\"start\": 42}")
      (spit (io/file data-dir "someJob/anotherBuild.json") "{\"start\": 12}")
      (is (= [{:start 42} {:start 12}]
             (storage/load-builds data-dir)))))

  (testing "should return builds of different jobs"
    (let [data-dir (create-tmp-dir "test-storage")]
      (.mkdirs (io/file data-dir "someJob"))
      (spit (io/file data-dir "someJob/someBuild.json") "{\"start\": 42}")
      (.mkdirs (io/file data-dir "anotherJob"))
      (spit (io/file data-dir "anotherJob/someBuild.json") "{\"start\": 12}")
      (is (= [{:start 12} {:start 42}]
             (storage/load-builds data-dir))))))

(deftest test-store-testresults!
  (testing "should persist XML"
    (let [data-dir (create-tmp-dir "buildviz-data")]
      (storage/store-testresults! data-dir "anotherJob" "anotherBuild" "<xml>")
      (is (= "<xml>"
             (slurp (io/file data-dir "anotherJob/anotherBuild.xml"))))))

  (testing "should safely encode illegal filenames"
    (let [data-dir (create-tmp-dir "buildviz-data")]
      (storage/store-testresults! data-dir "aJob\n" ":" "<xml>")
      (is (= "<xml>"
             (slurp (io/file data-dir "aJob%0a/%3a.xml")))))))
