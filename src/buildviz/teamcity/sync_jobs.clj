(ns buildviz.teamcity.sync-jobs
  (:require [buildviz.teamcity
             [api :as api]
             [transform :as transform]]
            [buildviz.storage :as storage]
            [buildviz.util
             [json :as json]
             [url :as url]]
            [cheshire.core :as j]
            [clj-http.client :as client]
            [clj-progress.core :as progress]
            [clj-time
             [coerce :as tc]
             [core :as t]
             [format :as tf]]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [uritemplate-clj.core :as templ]))

(defn- all-builds-for-job [teamcity-url sync-start-time {:keys [id projectName name]}]
  (let [safe-build-start-time (t/minus sync-start-time (t/millis 1))]
    (->> (api/get-builds teamcity-url id)
         (map (fn [build]
                {:build build
                 :project-name projectName
                 :job-name name}))
         (take-while #(t/after? (transform/parse-build-date (get-in % [:build :startDate])) safe-build-start-time)))))

(defn- add-test-results [teamcity-url build]
  (assoc build :tests (api/get-test-report teamcity-url (:id (:build build)))))


(defn- store-junit-xml [data-dir job-name build-id test-results]
  (storage/store-testresults! data-dir job-name build-id (j/generate-string test-results)))

(defn- store [data-dir {:keys [job-name build-id build test-results]}]
  (log/info (format "Syncing %s %s: build" job-name build-id))
  (storage/store-build! data-dir job-name build-id build)
  (when-not (empty? test-results)
    (store-junit-xml data-dir job-name build-id test-results)))


(defn- sync-oldest-first-to-deal-with-cancellation [builds]
  (sort-by #(get-in % [:build :finishDate]) builds))

(defn- ignore-ongoing-builds [builds]
  (filter :result builds))

(defn- stop-at-first-non-finished-so-we-can-resume-later [builds]
  (take-while #(= "finished" (get-in % [:build :state])) builds))

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
    (->> projects
         (mapcat #(api/get-jobs teamcity-url %))
         (mapcat #(all-builds-for-job teamcity-url sync-start-time %))
         (progress/init "Syncing")
         sync-oldest-first-to-deal-with-cancellation
         stop-at-first-non-finished-so-we-can-resume-later
         (map (comp progress/tick
                    (partial store data-dir)
                    transform/teamcity-build->buildviz-build
                    (partial add-test-results teamcity-url)))
         dorun
         (progress/done))))
