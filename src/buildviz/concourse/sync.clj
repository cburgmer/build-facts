(ns buildviz.concourse.sync
  (:gen-class)
  (:require [buildviz.concourse.builds :as builds]
            [buildviz.storage :as storage]
            [clj-progress.core :as progress]
            [clj-time
             [core :as t]
             [format :as tf]
             [local :as l]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.tools.logging :as log]))

(def data-dir "data")

(def two-months-ago (t/minus (.withTimeAtStartOfDay (l/local-now)) (t/months 2)))

(def cli-options
  [["-h" "--help"]])

(defn usage [options-summary]
  (string/join "\n"
               [""
                "Syncs Concourse build history"
                ""
                "Usage: buildviz.concourse.sync [OPTIONS] CONCOURSE_TARGET"
                ""
                "CONCOURSE_TARGET       The target of the Concourse installation as provided to"
                "                       fly. To view your existing targets run 'fly targets', to"
                "                       login e.g."
                "                       'fly login --target build-data -c http://localhost:8080'."
                "                       fly can be downloaded from the Concourse main page."
                ""
                "Options"
                options-summary]))

(defn- assert-parameter [assert-func msg]
  (when (not (assert-func))
    (println msg)
    (System/exit 1)))

(defn- store [{:keys [job-name build-id build]}]
  (log/info (format "Syncing %s %s: build" job-name build-id))
  (storage/store-build! data-dir job-name build-id build))

(defn sync-jobs [concourse-target sync-start-time]
  (let [config (builds/config-for concourse-target)]
    (println (format "Concourse %s (%s)" (:base-url config) concourse-target) )
    (println (format "Finding all builds for syncing (starting from %s)..."
                     (tf/unparse (:date-time tf/formatters) sync-start-time)))
    (->> (builds/concourse-builds config sync-start-time)
         (progress/init "Syncing")
         (map progress/tick)
         (map store)
         dorun
         (progress/done))))

(defn -main [& c-args]
  (let [args (parse-opts c-args cli-options)]
    (when (:help (:options args))
      (println (usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [concourse-target (first (:arguments args))]
      (assert-parameter #(some? concourse-target) "The target for Concourse is required. Try --help.")

      (sync-jobs concourse-target two-months-ago))))
