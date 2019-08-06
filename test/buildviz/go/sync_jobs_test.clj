(ns buildviz.go.sync-jobs-test
  (:require [buildviz.go.sync-jobs :as sut]
            [buildviz.util.url :as url]
            [cheshire.core :as j]
            [clj-http.fake :as fake]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(defn- successful-json-response [body]
  (fn [_] {:status 200
           :body (j/generate-string body)}))

(defn- successful-response [body]
  (fn [_] {:status 200
           :body body}))

(defn- a-stage [name]
  {:name name})

(defn- a-pipeline [name & stages]
  {:name name
   :stages stages})

(defn- a-pipeline-group [name & pipelines]
  {:name name
   :pipelines pipelines})

(defn- a-config [& pipeline-groups]
  [["http://gocd:8513/api/config/pipeline_groups"
    (successful-json-response pipeline-groups)]])

(defn- a-job-run [name scheduled-date id]
  {:name name
   :scheduled_date scheduled-date
   :id id})

(defn- a-stage-run
  ([stage-name stage-run] {:name stage-name
                           :counter stage-run})
  ([pipeline-run stage-run result & jobs] {:pipeline_counter pipeline-run
                                           :counter stage-run
                                           :result result
                                           :jobs jobs}))

(defn- a-short-history [pipeline-name stage-name & stage-runs]
  [[(format "http://gocd:8513/api/stages/%s/%s/history/0" pipeline-name stage-name)
    (successful-json-response {:stages stage-runs})]
   [(format "http://gocd:8513/api/stages/%s/%s/history/%s" pipeline-name stage-name (count stage-runs))
    (successful-json-response {:stages '()})]])

(defn- a-simple-build-cause [revision id]
  {:modifications [{:revision revision}]
   :material {:id id}
   :changed false})

(defn- a-source-revision-build-cause [id revision]
  {:material {:id id :type "Git"}
   :modifications [{:revision revision}]
   :changed true})

(defn- a-pipeline-build-cause [id pipeline-name pipeline-run stage-name stage-run]
  {:material {:id id :type "Pipeline"}
   :modifications [{:revision (format "%s/%d/%s/%d" pipeline-name pipeline-run stage-name stage-run)}]
   :changed true})

(defn- a-pipeline-run [pipeline-name pipeline-run stages & revisions]
  [[(format "http://gocd:8513/api/pipelines/%s/instance/%s" pipeline-name pipeline-run)
    (successful-json-response {:stages stages
                               :build_cause {:material_revisions revisions
                                             :trigger_forced false}})]])

(defn- a-forced-pipeline-run [pipeline-name pipeline-run stages & revisions]
  [[(format "http://gocd:8513/api/pipelines/%s/instance/%s" pipeline-name pipeline-run)
    (successful-json-response {:stages stages
                               :build_cause {:material_revisions revisions
                                             :trigger_forced true}})]])

(defn- cruise-property [name value]
  (xml/element :property {:name name} (xml/->CData value)))

(defn- go-date-format [datetime]
  (tf/unparse (:date-time tf/formatters) datetime))

(defn- build-properties [{:keys [start-time end-time actual-stage-run outcome]}]
  (xml/emit-str (xml/element
                 :job {}
                 (xml/element
                  :properties {}
                  (cruise-property "cruise_job_result" outcome)
                  (cruise-property "cruise_timestamp_04_building" (go-date-format start-time))
                  (cruise-property "cruise_timestamp_06_completed" (go-date-format end-time))
                  (cruise-property "cruise_stage_counter" actual-stage-run)))))

(defn- a-builds-properties [job-id content]
  [[(format "http://gocd:8513/api/jobs/%s.xml" job-id)
    (successful-response (build-properties content))]])

(defn- a-file-list [pipeline-name pipeline-run stage-name stage-run build-name & files]
  [[(format "http://gocd:8513/files/%s/%s/%s/%s/%s.json"
            pipeline-name pipeline-run stage-name stage-run build-name)
    (successful-json-response files)]])

(defn- a-file [pipeline-name pipeline-run stage-name stage-run build-name file-path content]
  [[(format "http://gocd:8513/files/%s/%s/%s/%s/%s/%s"
            pipeline-name pipeline-run stage-name stage-run build-name file-path)
    (successful-response content)]])

(defn- serve-up [& routes]
  (->> routes
       (mapcat identity)
       (into {})))

(defn- create-tmp-dir [prefix] ; http://stackoverflow.com/questions/617414/create-a-temporary-directory-in-java
  (let [tmp-file (java.io.File/createTempFile prefix ".tmp")]
    (.delete tmp-file)
    (.getPath tmp-file)))

(def beginning-of-2016 (t/date-time 2016 1 1))


(deftest test-sync-jobs
  (testing "should handle no pipeline groups"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation (serve-up (a-config))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513") data-dir beginning-of-2016 nil)))
      (is (nil? (.listFiles (io/file data-dir))))))

  (testing "should handle empty pipeline group"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation (serve-up (a-config (a-pipeline-group "Development")))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513") data-dir beginning-of-2016 nil)))
      (is (nil? (.listFiles (io/file data-dir))))))

  (testing "should handle empty pipeline"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation (serve-up (a-config (a-pipeline-group "Development"
                                                                                (a-pipeline "Build"))))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513") data-dir beginning-of-2016 nil)))
      (is (nil? (.listFiles (io/file data-dir))))))

  (testing "should sync a stage"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)))
                  (a-pipeline-run "Build" 42
                                  [(a-stage-run "DoStuff" "1")]
                                  (a-simple-build-cause "AnotherPipeline/21" 7))
                  (a-builds-properties 321
                                       {:start-time (t/date-time 2017 1 1 10 0 0)
                                        :end-time (t/date-time 2017 1 1 12 0)
                                        :outcome "Passed"
                                        :actual-stage-run "1"})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= {:start 1483264800000
              :end 1483272000000
              :outcome "pass"
              :inputs [{:revision "AnotherPipeline/21", :sourceId 7}]}
             (j/parse-string (slurp (io/file data-dir "Build %3a%3a DoStuff/42.json")) true)))))

  (testing "should sync a build trigger from another pipeline"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)))
                  (a-pipeline-run "Build" 42
                                  [(a-stage-run "DoStuff" "1")]
                                  (a-pipeline-build-cause 7 "AnotherPipeline" 21 "AnotherStage" 2))
                  (a-builds-properties 321
                                       {:start-time (t/date-time 2017 1 1 10 0 0)
                                        :end-time (t/date-time 2017 1 1 12 0)
                                        :outcome "Passed"
                                        :actual-stage-run "1"})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= [{:jobName "AnotherPipeline :: AnotherStage"
               :buildId "21 (Run 2)"}]
             (-> (j/parse-string (slurp (io/file data-dir "Build %3a%3a DoStuff/42.json")) true)
                 :triggeredBy)))))

  (testing "should not sync a forced build trigger"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)))
                  (a-forced-pipeline-run "Build" 42
                                         [(a-stage-run "DoStuff" "1")]
                                         (a-pipeline-build-cause 7 "AnotherPipeline" 21 "AnotherStage" 2))
                  (a-builds-properties 321 {})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (nil? (-> (j/parse-string (slurp (io/file data-dir "Build %3a%3a DoStuff/42.json")) true)
                    :triggeredBy)))))

  (testing "should not count a source revision cause as pipeline trigger"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)))
                  (a-pipeline-run "Build" 42
                                  [(a-stage-run "DoStuff" "1")]
                                  (a-source-revision-build-cause 7 "abcd"))
                  (a-builds-properties 321
                                       {:start-time (t/date-time 2017 1 1 10 0 0)
                                        :end-time (t/date-time 2017 1 1 12 0)
                                        :outcome "Passed"
                                        :actual-stage-run "1"})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (nil? (-> (j/parse-string (slurp (io/file data-dir "Build %3a%3a DoStuff/42.json")) true)
                    :triggeredBy)))))

  (testing "should only sync build trigger from pipeline material for first stage"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff")
                                                          (a-stage "MoreStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1483261200000 321)))
                  (a-short-history "Build" "MoreStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "defaultJob" 1483268400099 4711)))
                  (a-pipeline-run "Build" 42
                                  [(a-stage-run "DoStuff" "1") (a-stage-run "MoreStuff" "1")]
                                  (a-pipeline-build-cause 7 "AnotherPipeline" 21 "AnotherStage" 2))
                  (a-builds-properties 321 {})
                  (a-builds-properties 4711 {})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob")
                  (a-file-list "Build" 42 "MoreStuff" "1" "defaultJob"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (let [pipeline-trigger {:jobName "AnotherPipeline :: AnotherStage"
                              :buildId "21 (Run 2)"}]
        (is (some #(= pipeline-trigger %)
                  (-> (j/parse-string (slurp (io/file data-dir "Build %3a%3a DoStuff/42.json")) true)
                      :triggeredBy)))
        (is (nil? (some #(= pipeline-trigger %)
                       (-> (j/parse-string (slurp (io/file data-dir "Build %3a%3a MoreStuff/42.json")) true)
                           :triggeredBy)))))))

  (testing "should sync build trigger from stage of same pipeline"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff")
                                                          (a-stage "MoreStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1483261200000 321)))
                  (a-short-history "Build" "MoreStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "defaultJob" 1483268400099 4711)))
                  (a-pipeline-run "Build" 42
                                  [(a-stage-run "DoStuff" "1") (a-stage-run "MoreStuff" "1")])
                  (a-builds-properties 321
                                       {:start-time (t/date-time 2017 1 1 10 0 0)
                                        :end-time (t/date-time 2017 1 1 12 0 0)
                                        :outcome "Passed"
                                        :actual-stage-run "1"})
                  (a-builds-properties 4711
                                       {:start-time (t/date-time 2017 1 1 12 0 10)
                                        :end-time (t/date-time 2017 1 1 12 0 50)
                                        :outcome "Passed"
                                        :actual-stage-run "1"})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob")
                  (a-file-list "Build" 42 "MoreStuff" "1" "defaultJob"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= [{:jobName "Build :: DoStuff"
               :buildId "42"}]
             (-> (j/parse-string (slurp (io/file data-dir "Build %3a%3a MoreStuff/42.json")) true)
                 :triggeredBy)))))

  (testing "should handle a rerun"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "2" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)))
                  (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                  (a-builds-properties 321
                                       {:start-time (t/date-time 2017 1 1 10 0 0)
                                        :end-time (t/date-time 2017 1 1 12 0)
                                        :outcome "Passed"
                                        :actual-stage-run "2"})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= ["42 (Run 2).json"]
             (->> (.listFiles (io/file data-dir "Build %3a%3a DoStuff"))
                  (map #(.getName %)))))))

  (testing "should not sync build trigger for re-run of stage"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff")
                                                          (a-stage "MoreStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1483261200000 321)))
                  (a-short-history "Build" "MoreStuff"
                                   (a-stage-run 42 "2" "Passed"
                                                (a-job-run "defaultJob" 1483268400099 4711)))
                  (a-pipeline-run "Build" 42
                                  [(a-stage-run "DoStuff" "1") (a-stage-run "MoreStuff" "2")])
                  (a-builds-properties 321
                                       {:start-time (t/date-time 2017 1 1 10 0 0)
                                        :end-time (t/date-time 2017 1 1 12 0 0)
                                        :outcome "Passed"
                                        :actual-stage-run "1"})
                  (a-builds-properties 4711
                                       {:start-time (t/date-time 2017 1 1 12 0 10)
                                        :end-time (t/date-time 2017 1 1 12 0 50)
                                        :outcome "Passed"
                                        :actual-stage-run "1"})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob")
                  (a-file-list "Build" 42 "MoreStuff" "1" "defaultJob"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (nil? (-> (j/parse-string (slurp (io/file data-dir "Build %3a%3a MoreStuff/42 (Run 2).json")) true)
                    :triggeredBy)))))

  (testing "should sync a failing stage"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Failed"
                                                (a-job-run "AlphaJob" 1493201298062 321)))
                  (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                  (a-builds-properties 321
                                       {:start-time (t/date-time 2017 1 1 10 0 0)
                                        :end-time (t/date-time 2017 1 1 12 0)
                                        :outcome "Failed"
                                        :actual-stage-run "1"})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= {:start 1483264800000
              :end 1483272000000
              :outcome "fail"
              :inputs []}
             (j/parse-string (slurp (io/file data-dir "Build %3a%3a DoStuff/42.json")) true)))))

  (testing "should ignore an ongoing stage"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Unknown"
                                                (a-job-run "AlphaJob" 1493201298062 321))))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (nil? (.listFiles (io/file data-dir))))))

  (testing "should ignore a stage who's job ran before the sync date offset"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob"
                                                           (- (tc/to-long beginning-of-2016)
                                                              2)
                                                           321)
                                                (a-job-run "BetaJob"
                                                           (+ (tc/to-long beginning-of-2016)
                                                              9001)
                                                           987))))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (nil? (.listFiles (io/file data-dir))))))

  (testing "should only sync stage of pipeline that's after the sync date offset"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff")
                                                          (a-stage "SomeMore"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob"
                                                           (- (tc/to-long beginning-of-2016)
                                                              2)
                                                           321)))
                  (a-short-history "Build" "SomeMore"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "SomeJob"
                                                           (tc/to-long beginning-of-2016)
                                                           987)))
                  (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                  (a-builds-properties 987 {})
                  (a-file-list "Build" 42 "SomeMore" "1" "SomeJob"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= ["Build %3a%3a SomeMore"]
             (->> (.listFiles (io/file data-dir))
                  (map #(.getName %)))))))

  (testing "should sync test results"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)))
                  (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                  (a-builds-properties 321 {})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                               {:files [{:name "dontcare.log"
                                         :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/dontcare.log"}
                                        {:name "results.xml"
                                         :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/results.xml"}]})
                  (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/results.xml"
                          "<testsuites></testsuites>"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuites></testsuites>"
             (slurp (io/file data-dir "Build %3a%3a DoStuff/42.xml"))))))

  (testing "should sync multiple test results in one job"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)))
                  (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                  (a-builds-properties 321 {})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                               {:name "one_result.xml"
                                :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/one_result.xml"}
                               {:name "others.xml"
                                :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/others.xml"})
                  (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "one_result.xml"
                          "<testsuite name=\"one\"></testsuite>")
                  (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "others.xml"
                          "<testsuites><testsuite name=\"other\"></testsuite></testsuites>"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuites><testsuite name=\"one\"></testsuite><testsuite name=\"other\"></testsuite></testsuites>"
             (slurp (io/file data-dir "Build %3a%3a DoStuff/42.xml"))))))

  (testing "should combine test results for two jobs"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)
                                                (a-job-run "BetaJob" 1493201298062 987)))
                  (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                  (a-builds-properties 321 {})
                  (a-builds-properties 987 {})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                               {:files [{:name "results.xml"
                                         :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/results.xml"}]})
                  (a-file-list "Build" 42 "DoStuff" "1" "BetaJob"
                               {:files [{:name "results.xml"
                                         :url "http://example.com/something/files/Build/42/DoStuff/1/BetaJob/tmp/results.xml"}]})
                  (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/results.xml"
                          "<testsuites><testsuite name=\"Alpha\"></testsuite></testsuites>")
                  (a-file "Build" 42 "DoStuff" "1" "BetaJob" "tmp/results.xml"
                          "<testsuites><testsuite name=\"Beta\"></testsuite></testsuites>"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuites><testsuite name=\"Alpha\"></testsuite><testsuite name=\"Beta\"></testsuite></testsuites>"
             (slurp (io/file data-dir "Build %3a%3a DoStuff/42.xml"))))))

  (testing "should not store test results if one job has invalid XML"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)
                                                (a-job-run "BetaJob" 1493201298062 987)))
                  (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                  (a-builds-properties 321 {})
                  (a-builds-properties 987 {})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                               {:files [{:name "results.xml"
                                         :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/results.xml"}]})
                  (a-file-list "Build" 42 "DoStuff" "1" "BetaJob"
                               {:files [{:name "results.xml"
                                         :url "http://example.com/something/files/Build/42/DoStuff/1/BetaJob/tmp/results.xml"}]})
                  (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/results.xml"
                          "<testsuites><testsuite name=\"Alpha\"></testsuite></testsuites>")
                  (a-file "Build" 42 "DoStuff" "1" "BetaJob" "tmp/results.xml"
                          "<testsuite>invalid xml"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= ["42.json"]
             (->> (io/file data-dir "Build %3a%3a DoStuff")
                  .listFiles
                  (map #(.getName %)))))))

  (testing "should store test results even if one job has no XML"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)
                                                (a-job-run "BetaJob" 1493201298062 987)))
                  (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                  (a-builds-properties 321 {})
                  (a-builds-properties 987 {})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                               {:files [{:name "results.xml"
                                         :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/results.xml"}]})
                  (a-file-list "Build" 42 "DoStuff" "1" "BetaJob")
                  (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/results.xml"
                          "<testsuites><testsuite name=\"Alpha\"></testsuite></testsuites>"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuites><testsuite name=\"Alpha\"></testsuite></testsuites>"
             (slurp (io/file data-dir "Build %3a%3a DoStuff/42.xml"))))))

  (testing "should now include not JUnit XML file"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-config (a-pipeline-group "Development"
                                              (a-pipeline "Build"
                                                          (a-stage "DoStuff"))))
                  (a-short-history "Build" "DoStuff"
                                   (a-stage-run 42 "1" "Passed"
                                                (a-job-run "AlphaJob" 1493201298062 321)))
                  (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                  (a-builds-properties 321 {})
                  (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                               {:files [{:name "nontest.xml"
                                         :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/nontest.xml"}
                                        {:name "results.xml"
                                         :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/results.xml"}]})
                  (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/nontest.xml"
                          "<?xml version=\"1.0\" encoding=\"UTF-8\"?><someNode><contentNode></contentNode></someNode>")
                  (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/results.xml"
                          "<?xml version=\"1.0\" encoding=\"UTF-8\"?> <!-- comments are fine -->  <testsuites></testsuites>"))
        (with-out-str (sut/sync-stages (url/url "http://gocd:8513")
                                       data-dir
                                       beginning-of-2016 nil)))
      (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuites></testsuites>"
             (slurp (io/file data-dir "Build %3a%3a DoStuff/42.xml")))))))
