(ns buildviz.teamcity.sync-jobs
  (:require [buildviz.teamcity.builds :as builds]
            [buildviz.storage :as storage]
            [cheshire.core :as j]
            [clj-progress.core :as progress]
            [clj-time
             [coerce :as tc]
             [core :as t]
             [format :as tf]]
            [clojure.tools.logging :as log]))

(defn- store-test-results [data-dir job-name build-id test-results]
  (storage/store-testresults! data-dir job-name build-id (j/generate-string test-results)))

(defn- store [data-dir {:keys [job-name build-id build test-results]}]
  (log/info (format "Syncing %s %s: build" job-name build-id))
  (storage/store-build! data-dir job-name build-id build)
  (when-not (empty? test-results)
    (store-test-results data-dir job-name build-id test-results)))

(defn- last-sync-date [builds]
  (when builds
    (tc/from-long (apply max (map :start builds)))))

(defn- sync-start [data-dir default-sync-start user-sync-start]
  (let [builds (seq (storage/load-builds data-dir))]
    (or user-sync-start
        (last-sync-date builds)
        default-sync-start)))

(defn sync-jobs [teamcity-url data-dir projects default-sync-start user-sync-start]
  (println "TeamCity" (str teamcity-url) projects)
  (let [sync-start-time (sync-start data-dir default-sync-start user-sync-start)]
    (println (format "Finding all builds for syncing (starting from %s)..."
                     (tf/unparse (:date-time tf/formatters) sync-start-time)))
    (->> (builds/teamcity-builds {:base-url teamcity-url
                                  :projects projects}
                                 sync-start-time)
         (progress/init "Syncing")
         (map (comp progress/tick
                    (partial store data-dir)))
         dorun
         (progress/done))))
