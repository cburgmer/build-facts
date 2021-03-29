(ns build-facts.sync
  (:gen-class)
  (:require [build-facts.concourse.builds :as builds]
            [build-facts.storage :as storage]
            [build-facts.util.json :as json]
            [cheshire.core :as j]
            [progrock.core :as pr]
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
  {:time (int (/ (:start build)
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

(defn- run-with-progress! [console? fn builds]
  (loop [builds-by-job builds
         bar (pr/progress-bar (count builds))]
    (if (empty? builds-by-job)
      (when console? (pr/print (pr/done bar)))
      (do (when console? (pr/print bar))
          (fn (first builds-by-job))
          (recur (next builds-by-job)
                 (pr/tick bar))))))

(defn- build-recent? [{start :start} sync-start-time]
  (or (nil? start)
      (t/after? (tc/from-long start) sync-start-time)))

(defn- builds-for-job [job-name all-builds-for-job sync-start-time state]
  (let [last-sync-time (get-in state ["jobs" job-name "lastStart"])
        sync-start-time (or (when last-sync-time
                              (tc/from-long last-sync-time))
                            sync-start-time)]
    (->> all-builds-for-job
         (take-while #(build-recent? % sync-start-time))
         reverse
         (take-while (fn [{outcome :outcome}] (or (= outcome "pass")
                                                  (= outcome "fail")))))))

(defn- read-state [state-file-path]
  (when state-file-path
    (let [state-file (io/file state-file-path)]
      (when (.exists state-file)
        (j/parse-string (slurp state-file-path))))))

(defn- update-state [state builds]
  (let [existing-state (or (get state "jobs")
                           {})
        pruned-state (select-keys existing-state
                                  (map (fn [[job-name]] job-name) builds))]
    (->> builds
         (remove (fn [[job-name builds]] (empty? builds)))
         (map (fn [[job-name builds]] [job-name {:lastStart (:start (last builds))}]))
         (into pruned-state)
         (assoc {} :jobs))))

(defn- write-state [state-file-path state]
  (when state-file-path
    (spit state-file-path (j/generate-string state))))

(defn- builds-to-sync [sync-start-time state all-builds]
  (map (fn [[job-name all-builds-for-job]]
         [job-name (lazy-seq (builds-for-job job-name all-builds-for-job sync-start-time state))])
       all-builds))

(defn sync-builds [{:keys [base-url
                           user-sync-start-time
                           state-file-path
                           splunkFormat?
                           output]}
                   fetch-all-builds]
  (let [state (read-state state-file-path)
        sync-start-time (or user-sync-start-time
                            two-months-ago)
        console? (some? output)]
    (log console? (if state
                    (format "Resuming %s from last sync..." base-url)
                    (format "Starting %s from %s..."
                            base-url
                            (tf/unparse (:date-time tf/formatters) user-sync-start-time))))
    (let [builds (builds-to-sync sync-start-time state (fetch-all-builds))]
      (run-with-progress! console?
                          (fn [[job-name builds]] (run! #(output! output splunkFormat? %) builds))
                          builds)
      (->> builds
           (update-state state)
           (write-state state-file-path))
      (log console? (format "Synced %s builds" (count (mapcat (fn [[_ builds]] builds) builds)))))))
