(ns build-facts.jenkins
  (:require [build-facts.shared :as shared]
            [build-facts.sync :as sync]
            [build-facts.jenkins.builds :as builds]
            [build-facts.util.url :as url]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]))

(defn jenkins-usage [options-summary]
  (string/join "\n"
               ["Syncs Jenkins build history"
                ""
                "Usage: build-facts.main [OPTIONS] jenkins JENKINS_URL"
                ""
                "Options:"
                options-summary
                ""
                "Action arguments:"
                ""
                "JENKINS_URL            The URL of the Jenkins installation. Provide the URL of"
                "                       a view to limit the sync to respective jobs."
                ""
                "Environment variables:"
                ""
                "JENKINS_USER           Username for basic auth"
                "JENKINS_PASSWORD       Password for basic auth"]))

(defn parse-options [c-args]
  (let [args (parse-opts c-args shared/cli-options)]
    (when (:help (:options args))
      (println (jenkins-usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [base-url (first (:arguments args))]
      (shared/assert-parameter #(some? base-url) "The URL for Jenkins is required. Try --help.")

      (merge (:options args)
             {:base-url (url/url base-url)}))))

(defn run [options]
  (let [jenkins-options (merge options
                               (parse-options (:action-args options)))]
    (sync/sync-builds-v2 jenkins-options
                         #(builds/jenkins-builds jenkins-options))))
