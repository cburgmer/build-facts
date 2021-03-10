(ns buildviz.go.sync-jobs
  (:require [buildviz.go.builds :as builds]
            [buildviz.go.junit :as junit]
            [buildviz.storage :as storage]
            [clj-progress.core :as progress]
            [clj-time.format :as tf]
            [clojure.tools.logging :as log])
  (:import [javax.xml.stream XMLStreamException]))

;; upload

(defn- store-junit-xml [data-dir job-name build-no junit-xml]
  (try
    (let [xml-content (junit/merge-junit-xml junit-xml)]
      (storage/store-testresults! data-dir job-name build-no xml-content))
    (catch javax.xml.stream.XMLStreamException e
      (do
        (log/errorf e "Unable parse JUnit XML from artifacts for %s %s." job-name build-no)
        (log/info "Offending XML content is:\n" junit-xml)))))

(defn- store [data-dir {job-name :job-name build-no :build-id build :build junit-xml :junit-xml}]
  (log/info (format "Syncing %s %s: build" job-name build-no))
  (storage/store-build! data-dir job-name build-no build)
  (when (some? junit-xml)
    (store-junit-xml data-dir job-name build-no junit-xml)))

;; run

(defn- emit-start [go-url sync-start-time]
  (println "Go" (str go-url))
  (println (format "Finding all pipeline runs for syncing (starting from %s)..."
                   (tf/unparse (:date-time tf/formatters) sync-start-time))))

(defn sync-stages [go-url data-dir sync-start-time selected-pipeline-group-names]
  (emit-start go-url sync-start-time)
  (->> (builds/gocd-builds {:base-url go-url
                            :pipeline-group-names selected-pipeline-group-names}
                           sync-start-time)
       (progress/init "Syncing")
       (map #(store data-dir %))
       (map progress/tick)
       dorun
       (progress/done)))
