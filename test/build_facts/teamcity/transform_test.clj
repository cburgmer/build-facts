(ns build-facts.teamcity.transform-test
  (:require [build-facts.teamcity.transform :as sut]
            [clojure.test :refer :all]))

(defn- a-teamcity-build [build]
  {:job-name "a_job"
   :project-name "a_project"
   :build (merge {:number "42"
                  :status "SUCCESS"
                  :startDate "20160401T003701+0000"
                  :finishDate "20160401T003707+0000"}
                 build)})

(defn- a-teamcity-build-with-test [test]
  (-> (a-teamcity-build {})
      (assoc :tests [(merge {:name "a test suite: the.class.the test"
                             :status "SUCCESS"}
                            test)])))

(deftest test-teamcity-build->build-facts-build
  (testing "should return job name"
    (is (= "some_project some_job"
           (:job-name (sut/teamcity-build->build-facts-build (-> (a-teamcity-build {})
                                                              (assoc :job-name "some_job"
                                                                     :project-name "some_project")))))))
  (testing "should return build id"
    (is (= "21"
           (:build-id (sut/teamcity-build->build-facts-build (a-teamcity-build {:number "21"}))))))

  (testing "should return successful status"
    (is (= "pass"
           (:outcome  (sut/teamcity-build->build-facts-build (a-teamcity-build {:status "SUCCESS"}))))))
  (testing "should return failed status"
    (is (= "fail"
           (:outcome (sut/teamcity-build->build-facts-build (a-teamcity-build {:status "FAILURE"}))))))
  (testing "should return start timestamp"
    (is (= 1459585432000
           (:start (sut/teamcity-build->build-facts-build (a-teamcity-build {:startDate "20160402T082352+0000"}))))))
  (testing "should return end timestamp"
    (is (= 1459585450000
           (:end (sut/teamcity-build->build-facts-build (a-teamcity-build {:finishDate "20160402T082410+0000"}))))))

  (testing "should return triggeredBy information"
    (is (= [{:job-name "project job_name"
             :build-id "42"}]
           (->> (a-teamcity-build {:snapshot-dependencies {:build [{:buildType {:name "job_name"
                                                                                :projectName "project"}
                                                                    :number "42"}]}
                                   :triggered {:type "finishBuild"}})
                sut/teamcity-build->build-facts-build
                :triggered-by))))

  (testing "should return triggeredBy information for legacy response"
    (is (= [{:job-name "project job_name"
             :build-id "42"}]
           (->> (a-teamcity-build {:snapshot-dependencies {:build [{:buildType {:name "job_name"
                                                                                :projectName "project"}
                                                                    :number "42"}]}
                                   :triggered {:type "unknown"}})
                sut/teamcity-build->build-facts-build
                :triggered-by))))

  (testing "should omit build trigger if triggered by user due to temporal disconnect"
    (is (not (contains? (sut/teamcity-build->build-facts-build
                         (a-teamcity-build
                          {:snapshot-dependencies {:build [{:buildType {:name "job_name"
                                                                        :projectName "project"}
                                                            :number 42}]}
                           :triggered {:type "user"}}))
                        :triggered-by))))

  (testing "should return tests"
    (is (= [{:name "suite"
             :children [{:name "the test"
                         :classname "class"
                         :status "pass"
                         :runtime 42}]}]
           (:test-results (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:name "suite: class.the test"
                                                                                           :status "SUCCESS"
                                                                                           :duration 42}))))))
  (testing "should return failing test"
    (is (= "fail"
           (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:status "FAILURE"}))
               :test-results
               first
               :children
               first
               :status))))
  (testing "should return skipped test"
    (is (= "skipped"
           (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:status "UNKNOWN"
                                                                                :ignored true}))
               :test-results
               first
               :children
               first
               :status))))
  (testing "should return runtime"
    (is (= 21
           (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:duration 21}))
               :test-results
               first
               :children
               first
               :runtime))))
  (testing "should return runtime 0 if duration is missing to work around https://youtrack.jetbrains.com/issue/TW-45065"
    (is (= 0
         (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {}))
                        :test-results
                        first
                        :children
                        first
                        :runtime))))
  (testing "should extract classname for JUnit origin"
    (is (= "the.class"
           (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:name "suite: the.class.the test"}))
               :test-results
               first
               :children
               first
               :classname))))
  (testing "should handle no-suite information for JUnit origin"
    (is (= {:name "<no suite>"
            :children [{:classname "the.class"
                        :name "the test"
                        :status "pass"
                        :runtime 0}]}
           (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:name "the.class.the test"}))
               :test-results
               first))))
  (testing "should handle JUnit DataProvider name formatting"
    (is (= {:name "The Suite"
            :children [{:classname "the.class"
                        :name "shouldDoSomething[2](Some: String)"
                        :status "pass"
                        :runtime 0}]}
           (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:name "The Suite: the.class.shouldDoSomething[2](Some: String)"}))
               :test-results
               first))))
  (testing "should handle line breaks in JUnit DataProvide formatting"
    (is (= {:name "The Suite"
            :children [{:classname "the.class"
                        :name "shouldDoSomething[2](Some: String\nwith\nline\nbreaks)"
                        :status "pass"
                        :runtime 0}]}
           (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:name "The Suite: the.class.shouldDoSomething[2](Some: String\nwith\nline\nbreaks)"}))
               :test-results
               first))))
  (testing "should for now not care for nested suites for JUnit origin"
    (is (= "suite: nested suite"
           (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:name "suite: nested suite: the.class.the test"}))
               :test-results
               first
               :name))))
  (testing "should extract classname for non-JUnit origin"
    (is (= {:name "<empty>"
            :children [{:classname "The Class: Sub section"
                        :name "Test description"
                        :status "pass"
                        :runtime 0}]}
           (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:name "The Class: Sub section: Test description"}))
               :test-results
               first))))
  (testing "should handle colons in test name for non-JUnit origin"
    (is (= {:classname "The Class: Sub section"
            :name "Test:description"
            :status "pass"
            :runtime 0}
           (-> (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {:name "The Class: Sub section: Test:description"}))
               :test-results
               first
               :children
               first))))
  (testing "should fallback to RSpec style test name pattern if at least one of the tests does not match JUnit pattern"
    ;; This crude logic should save us from implementing either
    ;; - a configuration item for the user to specify which format is correct
    ;; - parsing the job's configuration and guessing what underlying reporter was used to generate the test report
    (is (= {:classname "The Subject"
            :name "test.description.with.dots"
            :status "pass"
            :runtime 0}
           (-> (sut/teamcity-build->build-facts-build (-> (a-teamcity-build {})
                                                       (assoc :tests [{:name "The Subject: test.description.with.dots" :status "SUCCESS"}
                                                                      {:name "The Class: Sub section: Test description" :status "SUCCESS"}])))
               :test-results
               first
               :children
               first))))

  (testing "should extract inputs"
    (is (= [{:source-id "https://github.com/cburgmer/build-facts.git"
             :revision "49b2c70535fa0ed936697c9b352495c6a835f90b"}]
           (-> (sut/teamcity-build->build-facts-build
                (a-teamcity-build {:revisions {:revision [{:version "49b2c70535fa0ed936697c9b352495c6a835f90b"
                                                           :vcs-root-instance {:name "https://github.com/cburgmer/build-facts.git"}}]}}))
               :inputs))))
  (testing "should handle optional inputs"
    (is (not (contains? (sut/teamcity-build->build-facts-build (a-teamcity-build-with-test {}))
                        :inputs)))))
