(ns buildviz.controllers.job-runtime-test
  (:require [buildviz.test-utils :refer :all]
            [clj-time
             [coerce :as tc]
             [core :as t]]
            [clojure
             [string :as str]
             [test :refer :all]]))

(def a-timestamp (tc/to-long (t/from-time-zone (t/date-time 1986 10 14 4 3 27 456) (t/default-time-zone))))
(def a-day (* 24 60 60 1000))

(deftest test-get-job-runtime
  (testing "GET should return 200"
    (is (= 200
           (:status (get-request (the-app) "/jobruntime")))))

  (testing "GET should return empty list by default"
    (is (= "date\n"
           (:body (get-request (the-app) "/jobruntime")))))

  (testing "GET should return the average runtime for each job as well as total"
    (let [app (the-app {"aBuild" {1 {:start a-timestamp :end (+ a-timestamp 1000)}
                                  2 {:start (+ a-timestamp 2000) :end (+ a-timestamp 4001)}
                                  3 {:start (+ a-timestamp a-day) :end (+ a-timestamp a-day 4000)}}
                        "anotherBuild" {1 {:start a-timestamp :end (+ a-timestamp 4000)}}
                        "buildWithoutTimestamps" {1 {:outcome "pass" :start a-timestamp}}}
                       {})]
      (is (= (str/join "\n" ["date,aBuild,anotherBuild"
                             (format "1986-10-14,%.8f,%.8f" (float (/ 1501 a-day)) (float (/ 4000 a-day)))
                             (format "1986-10-15,%.8f," (float (/ 4000 a-day)))
                             ""])
             (:body (get-request app "/jobruntime"))))))

  (testing "should respect 'from' filter"
    (let [app (the-app {"aBuild" {1 {:start (- a-timestamp a-day), :end (- a-timestamp a-day)}}
                        "anotherBuild" {1 {:start a-timestamp :end a-timestamp}}}
                       {})]
      (is (= (str/join "\n" ["date,anotherBuild"
                             (format "1986-10-14,%.8f" 0.0)
                             ""])
             (:body (get-request app "/jobruntime" {"from" a-timestamp}))))))

  (testing "GET should return empty list for JSON"
    (is (= []
           (json-body (json-get-request (the-app) "/jobruntime")))))

  (testing "should respond with JSON"
    (let [app (the-app {"aBuild" {1 {:start a-timestamp :end (+ a-timestamp 1000)}
                                  2 {:start (+ a-timestamp 2000) :end (+ a-timestamp 4001)}
                                  3 {:start (+ a-timestamp a-day) :end (+ a-timestamp a-day 4000)}}
                        "anotherBuild" {1 {:start a-timestamp :end (+ a-timestamp 4000)}}}
                       {})]
      (is (= [{"job" "aBuild"
               "runtimes" [{"date" "1986-10-14"
                            "runtime" 1501}
                           {"date" "1986-10-15"
                            "runtime" 4000}]}
              {"job" "anotherBuild"
               "runtimes" [{"date" "1986-10-14"
                            "runtime" 4000}]}]
             (json-body (json-get-request app "/jobruntime")))))))