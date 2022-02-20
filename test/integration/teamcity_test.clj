(ns integration.teamcity-test
  (:require [build-facts.main :as sut]
            [cheshire.core :as j]
            [clojure.test :refer :all])
  (:import com.github.tomakehurst.wiremock.core.WireMockConfiguration
           com.github.tomakehurst.wiremock.WireMockServer))

(defmacro with-no-err [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#]
       ~@body)))

(deftest test-teamcity
  (let [config (doto (new WireMockConfiguration)
                 (.port 3334)
                 (.usingFilesUnderClasspath "test/resources/teamcity"))
        server (new WireMockServer config)]
    (.start server)

    (let [output (with-out-str
                   (with-no-err
                     (sut/-main "teamcity" (str "http://localhost:" (.port server)) "-p" "SimpleSetup" "--from" "2000-01-01")))
          json-stream (->> output
                           (clojure.string/split-lines)
                           (map #(j/parse-string % true)))]

      (testing "includes all jobs"
        (is (= ["SimpleSetup / SubProject Test"
                "SimpleSetup Deploy"
                "SimpleSetup RSpec"
                "SimpleSetup RSpec JUnit XML"
                "SimpleSetup Test"]
               (->> json-stream
                    (map :jobName)
                    dedupe
                    sort))))

      (testing "includes triggeredBy"
        (is (= ["SimpleSetup Test"]
               (->> json-stream
                    (mapcat :triggeredBy)
                    (remove nil?)
                    (map :jobName)
                    dedupe))))

      (testing "includes inputs with sourceId"
        (is (= ["https://github.com/cburgmer/buildviz.git"]
               (->> json-stream
                    (mapcat :inputs)
                    (remove nil?)
                    (map :sourceId)
                    dedupe))))

      (testing "includes testResults"
        (is (= ["SimpleSetup / SubProject Test"
                "SimpleSetup Test"]
               (->> json-stream
                    (filter :testResults)
                    (map :jobName)
                    dedupe
                    sort)))))

    (.stop server)))
