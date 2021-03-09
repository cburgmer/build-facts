(ns buildviz.jenkins
  (:require [buildviz.shared :as shared]
            [buildviz.sync :as sync]
            [buildviz.jenkins.builds :as builds]
            [buildviz.util.url :as url]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]))

(defn jenkins-usage [options-summary]
  (string/join "\n"
               ["Syncs Jenkins build history"
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
    (sync/sync-builds jenkins-options
                      #(builds/jenkins-builds jenkins-options %))))
