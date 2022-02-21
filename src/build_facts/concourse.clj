(ns build-facts.concourse
  (:require [build-facts.shared :as shared]
            [build-facts.sync :as sync]
            [build-facts.concourse.builds :as builds]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]))

(defn concourse-usage [options-summary]
  (string/join "\n"
               ["Syncs Concourse build history"
                ""
                "Usage: build-facts.main [OPTIONS] concourse CONCOURSE_TARGET"
                ""
                "Options:"
                options-summary
                ""
                "Action arguments:"
                ""
                "CONCOURSE_TARGET       The target of the Concourse installation as provided to"
                "                       fly. To view your existing targets run 'fly targets', to"
                "                       login e.g."
                "                       'fly login --target build-facts -c http://localhost:8080'."
                "                       fly can be downloaded from the Concourse main page."]))

(defn- parse-options [c-args]
  (let [args (parse-opts c-args shared/cli-options)]
    (when (:help (:options args))
      (println (concourse-usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [concourse-target (first (:arguments args))]
      (shared/assert-parameter #(some? concourse-target) "The target for Concourse is required. Try --help.")

      (merge (:options args)
             {:concourse-target concourse-target}))))

(defn config-for [concourse-target]
  (let [flyrc (io/file (System/getProperty "user.home") ".flyrc")
        config (-> (slurp flyrc)
                   (yaml/parse-string :keywords false)
                   (get "targets")
                   (get concourse-target))]
    (if (= (-> config
               (get "token")
               (get "type"))
           "bearer")
      {:base-url (get config "api")
       :team-name (get config "team")
       :bearer-token (-> config
                         (get "token")
                         (get "value"))
       :concourse-target concourse-target}
      (throw (Exception.
              (format "No token found for concourse target '%s'. Please run 'fly login --target %s -c CONCOURSE_URL' or provide a correct target."
                      concourse-target
                      concourse-target))))))

(defn run [options]
  (let [concourse-options (merge options
                                 (parse-options (:action-args options)))
        config (config-for (:concourse-target concourse-options))]
    (sync/sync-builds (assoc concourse-options :base-url (:base-url config))
                      #(builds/concourse-builds config))))
