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
      (is (= ["42.json"] ;; only json file stored, not the test xml
             (->> (io/file data-dir "Build %3a%3a DoStuff")
                  .listFiles
                  (map #(.getName %))))))))
