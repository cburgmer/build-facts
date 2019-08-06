(ns buildviz.jenkins.sync
  (:gen-class)
  (:require [buildviz.jenkins.sync-jobs :as sync-jobs]
            [buildviz.util.url :as url]
            [buildviz.storage :as storage]
            [cheshire.core :as j]
            [clj-http.client :as client]
            [clj-time
             [coerce :as tc]
             [core :as t]
             [format :as tf]
             [local :as l]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]))

(def data-dir "data")

(def tz (t/default-time-zone))

(def date-formatter (tf/formatter tz "YYYY-MM-dd" "YYYY/MM/dd" "YYYYMMdd" "dd.MM.YYYY"))

(def cli-options [
   ["-f" "--from DATE" "Date from which on builds are loaded, if not specified tries to pick up where the last run finished"
    :id :sync-start-time
    :parse-fn #(tf/parse date-formatter %)]
   ["-h" "--help"]])

(defn usage [options-summary]
  (string/join "\n"
               [""
                "Syncs Jenkins build history"
                ""
                "Usage: buildviz.jenkins.sync [OPTIONS] JENKINS_URL"
                ""
                "JENKINS_URL            The URL of the Jenkins installation. Provide the URL of"
                "                       a view to limit the sync to respective jobs."
                ""
                "Options"
                options-summary]))


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
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [jenkins-url (url/url (first (:arguments args)))
          sync-start-time (get-start-date (:sync-start-time (:options args)))]

      (sync-jobs/sync-jobs jenkins-url data-dir sync-start-time))))
