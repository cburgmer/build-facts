(ns buildviz.main
  (:gen-class)
  (:require [buildviz.sync :as sync]
            [buildviz.concourse.builds :as concourse-builds]
            [buildviz.jenkins.sync-jobs :as jenkins-builds]
            [buildviz.storage :as storage]
            [buildviz.util.json :as json]
            [buildviz.util.url :as url]
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
    :id :user-sync-start-time
    :parse-fn #(tf/parse date-formatter %)]
   ["-o" "--output PATH" "Directory where build data is stored"
    :id :output]
   [nil "--splunk" "Output builds in Splunk HTTP Event Collector (HEC) format"
    :id :splunkFormat?]
   [nil "--state PATH" "Path to state file for resuming after last sync"
    :id :state-file-path]
   ["-h" "--help"]])

(defn usage [options-summary]
  (string/join "\n"
               ["Syncs build history"
                ""
                "Usage: buildviz.main [OPTIONS] action"
                ""
                "Options:"
                options-summary
                ""
                "Actions:"
                "  concourse    Reads build data from Concourse"
                "  jenkins      Reads build data from Jenkins"]))

(defn- assert-parameter [assert-func msg]
  (when (not (assert-func))
    (println msg)
    (System/exit 1)))

(defn- parse-options [c-args]
  (let [args (parse-opts c-args cli-options :in-order true)]
    (when (:help (:options args))
      (println (usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [action (first (:arguments args))]
      (assert-parameter #(some? action) "The action is required. Try --help.")

      (merge (:options args)
             {:action action
              :action-args (rest (:arguments args))}))))

(defn concourse-usage [options-summary]
  (string/join "\n"
               [""
                "Syncs Concourse build history"
                ""
                "Usage: buildviz.main [OPTIONS] concourse CONCOURSE_TARGET"
                ""
                "Options:"
                options-summary
                ""
                "Action arguments:"
                ""
                "CONCOURSE_TARGET       The target of the Concourse installation as provided to"
                "                       fly. To view your existing targets run 'fly targets', to"
                "                       login e.g."
                "                       'fly login --target build-data -c http://localhost:8080'."
                "                       fly can be downloaded from the Concourse main page."]))

(defn- parse-concourse-options [c-args]
  (let [args (parse-opts c-args cli-options)]
    (when (:help (:options args))
      (println (concourse-usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [concourse-target (first (:arguments args))]
      (assert-parameter #(some? concourse-target) "The target for Concourse is required. Try --help.")

      (merge (:options args)
             {:concourse-target concourse-target}))))

(defn jenkins-usage [options-summary]
  (string/join "\n"
               [""
                "Syncs Jenkins build history"
                ""
                "Usage: buildviz.main [OPTIONS] jenkins JENKINS_URL"
                ""
                "Options:"
                options-summary
                ""
                "Action arguments:"
                ""
                "JENKINS_URL            The URL of the Jenkins installation. Provide the URL of"
                "                       a view to limit the sync to respective jobs."]))

(defn- parse-jenkins-options [c-args]
  (let [args (parse-opts c-args cli-options)]
    (when (:help (:options args))
      (println (jenkins-usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [base-url (first (:arguments args))]
      (assert-parameter #(some? base-url) "The URL for Jenkins is required. Try --help.")

      (merge (:options args)
             {:base-url (url/url base-url)}))))

(defn -main [& c-args]
  (let [{:keys [action] :as options} (parse-options c-args)]

    (case action
      "concourse" (let [concourse-options (merge options
                                                 (parse-concourse-options (:action-args options)))
                        config (concourse-builds/config-for (:concourse-target concourse-options))]
                    (sync/sync-builds (assoc concourse-options :base-url (:base-url config))
                                      #(concourse-builds/concourse-builds config %)))
      "jenkins" (let [jenkins-options (merge options
                                             (parse-jenkins-options (:action-args options)))]
                  (sync/sync-builds jenkins-options
                                    #(jenkins-builds/jenkins-builds jenkins-options %)))
      (do (println "Unknown action. Try --help.")
          (System/exit 1)))))
