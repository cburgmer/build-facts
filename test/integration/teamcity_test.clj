(ns integration.teamcity-test
  (:require [build-facts.main :as sut]
            [cheshire.core :as j]
            [clojure.test :refer :all]
            [json-schema.core :as schema]
            [clj-http.client :as client]
            [build-facts.util.env :as env])
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

    (with-redefs [env/env-var (fn [var] (get {"TEAMCITY_USER" "admin"
                                              "TEAMCITY_PASSWORD" "admin"}
                                             var))]
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
                      sort))))

        (testing "has valid schema"
          (let [build-schema (slurp (clojure.java.io/resource "schema.json"))]
            (->> json-stream
                 (map #(schema/validate build-schema %))
                 doall)))

        (testing "user agent is sent"
          (let [response (client/post (str "http://localhost:" (.port server) "/__admin/requests/count")
                                      {:body (j/generate-string {:method "GET"
                                                                 :url "/httpAuth/app/rest/projects/SimpleSetup"
                                                                 :headers {:User-Agent {:matches "build-facts.*"}}})})]
            (is (< 0
                   (-> response
                       :body
                       (j/parse-string true)
                       :count)))))

        (testing "basic-auth is correctly set"
          (let [response (client/post (str "http://localhost:" (.port server) "/__admin/requests/count")
                                      {:body (j/generate-string {:method "GET"
                                                                 :url "/httpAuth/app/rest/projects/SimpleSetup"
                                                                 :headers {:Authorization {:matches "Basic YWRtaW46YWRtaW4="}}})})]
            (is (< 0
                   (-> response
                       :body
                       (j/parse-string true)
                       :count)))))))

    (.stop server)))
