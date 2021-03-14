(ns build-facts.sync
  (:gen-class)
  (:require [build-facts.concourse.builds :as builds]
            [build-facts.storage :as storage]
            [build-facts.util.json :as json]
            [clj-progress.core :as progress]
            [clj-time
             [core :as t]
             [coerce :as tc]
             [format :as tf]
             [local :as l]]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(def two-months-ago (t/minus (.withTimeAtStartOfDay (l/local-now)) (t/months 2)))

(defn- build->splunk-format [build] ;; https://docs.splunk.com/Documentation/Splunk/8.1.2/Data/FormateventsforHTTPEventCollector
  {:time (int (/ (:end build)
                 1000))
   :source "build-facts"
   :event build})

(defn- output! [output splunkFormat? {:keys [job-name build-id] :as build}]
  (log/info (format "Syncing %s %s: build" job-name build-id))
  (let [entity (if splunkFormat?
                (build->splunk-format build)
                build)]
    (if output
      (storage/store-build! output job-name build-id entity)
      (println (json/to-string entity))))
  build)

(defn- latest-build [builds]
  (when (> (count builds)
           0)
    (apply max-key #(:start %) builds)))


(defn- log [console? message]
  (if console?
    (println message)
    (binding [*out* *err*]
      (println message))))

(defn- with-progress [console? fn builds]
  (if console?
    (->> builds
         (progress/init "Syncing")
         (map progress/tick)
         (map fn)
         progress/done)
    (->> builds
         (map fn))))

(defn- write-state [state-file-path last-build]
  (when state-file-path
    (spit (io/file state-file-path)
          (json/to-string {:last-build-start (:start last-build)}))))

(defn- read-state [state-file-path]
  (when state-file-path
    (let [state-file (io/file state-file-path)]
      (when (.exists state-file)
        (json/from-string (slurp (io/file state-file-path)))))))

(defn sync-builds [{:keys [base-url
                           user-sync-start-time
                           output
                           splunkFormat?
                           state-file-path]}
                   fetch-builds]
  (let [state-last-sync-time (when-let [unix-time (:last-build-start (read-state state-file-path))]
                               (tc/from-long unix-time))
        sync-start-time (or state-last-sync-time
                            user-sync-start-time
                            two-months-ago)
        console? (some? output)]
    (log console? (format "Finding all builds for syncing from %s (starting from %s)..."
                          base-url
                          (tf/unparse (:date-time tf/formatters) sync-start-time)))

    (some->> (fetch-builds sync-start-time)
             (with-progress console? #(output! output splunkFormat? %))
             latest-build
             (write-state state-file-path))))
