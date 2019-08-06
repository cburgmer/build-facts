(ns buildviz.go.sync
  (:gen-class)
  (:require [buildviz.go.sync-jobs :as sync-jobs]
            [buildviz.util.url :as url]
            [buildviz.storage :as storage]
            [cheshire.core :as j]
            [clj-http.client :as client]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.local :as l]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]))

(def data-dir "data")

(def tz (t/default-time-zone))

(def date-formatter (tf/formatter tz "YYYY-MM-dd" "YYYY/MM/dd" "YYYYMMdd" "dd.MM.YYYY"))

(defn usage [options-summary]
  (str/join "\n"
            [""
             "Syncs GoCD build history with buildviz"
             ""
             "Usage: buildviz.go.sync [OPTIONS] GO_URL"
             ""
             "GO_URL            The URL of the GoCD installation"
             ""
             "Options"
             options-summary]))

(def cli-options
  [["-f" "--from DATE" "Date from which on builds are loaded, if not specified tries to pick up where the last run finished"
    :id :sync-start-time
    :parse-fn #(tf/parse date-formatter %)]
   ["-g" "--pipeline-group PIPELINE_GROUP" "Go pipeline groups to be synced, all by default"
    :id :pipeline-groups
    :default nil
    :assoc-fn (fn [previous key val] (assoc previous key (conj (get previous key) val)))]
   ["-h" "--help"]])


(def two-months-ago (t/minus (.withTimeAtStartOfDay (l/local-now)) (t/months 2)))

(defn- get-start-date [date-from-config]
  (if (some? date-from-config)
    date-from-config
    (if-let [builds (seq (storage/load-builds data-dir))]
      (tc/from-long (apply max (map :start builds)))
      two-months-ago)))

(defn -main [& c-args]
  (let [args (parse-opts c-args cli-options)]
    (when (or (:help (:options args))
              (empty? (:arguments args)))
      (println (usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (str/join "\n" (:errors args)))
      (System/exit 1))

    (let [go-url (url/url (first (:arguments args)))
          sync-start-time (get-start-date (:sync-start-time (:options args)))
          selected-pipeline-group-names (set (:pipeline-groups (:options args)))]

      (sync-jobs/sync-stages go-url data-dir sync-start-time selected-pipeline-group-names))))
