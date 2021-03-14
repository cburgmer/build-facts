(ns build-facts.shared
  (:require [clj-time
             [core :as t]
             [format :as tf]
             [local :as l]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]))

(def tz (t/default-time-zone))

(def date-formatter (tf/formatter tz "YYYY-MM-dd" "YYYY/MM/dd" "YYYYMMdd" "dd.MM.YYYY"))

(def cli-options
  [["-f" "--from DATE" "Date from which on builds are loaded"
    :id :user-sync-start-time
    :parse-fn #(tf/parse date-formatter %)]
   ["-o" "--output PATH" "Directory where build data is stored"
    :id :output]
   [nil "--splunk" "Output builds in Splunk HTTP Event Collector (HEC) format"
    :id :splunkFormat?]
   [nil "--state PATH" "Path to state file for resuming after last sync"
    :id :state-file-path]
   ["-h" "--help"]])

(defn assert-parameter [assert-func msg]
  (when (not (assert-func))
    (println msg)
    (System/exit 1)))
