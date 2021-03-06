(ns buildviz.concourse.sync
  (:gen-class)
  (:require [buildviz.concourse.builds :as builds]
            [buildviz.storage :as storage]
            [cheshire.core :as j]
            [clj-progress.core :as progress]
            [clj-time
             [core :as t]
             [coerce :as tc]
             [format :as tf]
             [local :as l]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(def two-months-ago (t/minus (.withTimeAtStartOfDay (l/local-now)) (t/months 2)))

(def tz (t/default-time-zone))

(def date-formatter (tf/formatter tz "YYYY-MM-dd" "YYYY/MM/dd" "YYYYMMdd" "dd.MM.YYYY"))

(def cli-options
  [["-f" "--from DATE" "Date from which on builds are loaded"
    :id :sync-start-time
    :parse-fn #(tf/parse date-formatter %)]
   ["-o" "--output PATH" "Directory where build data is stored"
    :id :output]
   ["" "--state PATH" "Path to state file for resuming after last sync"
    :id :state-file-path]
   ["-h" "--help"]])

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

(defn- parse-options [c-args]
  (let [args (parse-opts c-args cli-options)]
    (when (:help (:options args))
      (println (usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [concourse-target (first (:arguments args))]
      (assert-parameter #(some? concourse-target) "The target for Concourse is required. Try --help.")

      {:concourse-target concourse-target
       :user-sync-start-time (:sync-start-time (:options args))
       :output (:output (:options args))
       :state-file-path (:state-file-path (:options args))})))

(defn- store [output {:keys [job-name build-id build] :as bbuild}]
  (log/info (format "Syncing %s %s: build" job-name build-id))
  (if output
    (storage/store-build! output job-name build-id build)
    (println (j/generate-string build)))
  bbuild)

(defn- latest-build [builds]
  (when (> (count builds)
           0)
    (apply max-key #(:start (:build %)) builds)))


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

(defn- sync-jobs [concourse-target output sync-start-time]
  (let [config (builds/config-for concourse-target)
        console? (some? output)]
    (log console? (format "Concourse %s (%s)" (:base-url config) concourse-target))
    (log console? (format "Finding all builds for syncing (starting from %s)..."
                          (tf/unparse (:date-time tf/formatters) sync-start-time)))

    (->> (builds/concourse-builds config sync-start-time)
         (with-progress console? #(store output %))
         latest-build)))

(defn- write-state [state-file-path last-build]
  (when state-file-path
    (spit (io/file state-file-path)
          (j/generate-string {:lastBuildStart (:start (:build last-build))}))))

(defn- read-state [state-file-path]
  (when state-file-path
    (let [state-file (io/file state-file-path)]
      (when (.exists state-file)
        (j/parse-string (slurp (io/file state-file-path))
                        true)))))

(defn -main [& c-args]
  (let [{:keys [concourse-target
                user-sync-start-time
                output
                state-file-path
                state-last-sync-time]} (parse-options c-args)
        state-last-sync-time (when-let [unix-time (:lastBuildStart (read-state state-file-path))]
                               (tc/from-long unix-time))]
    (some->> (sync-jobs concourse-target
                        output
                        (or state-last-sync-time
                            user-sync-start-time
                            two-months-ago))
             (write-state state-file-path))))
