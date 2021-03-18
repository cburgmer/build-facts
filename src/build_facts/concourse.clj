(ns build-facts.concourse
  (:require [build-facts.shared :as shared]
            [build-facts.sync :as sync]
            [build-facts.concourse.builds :as builds]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]))

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

(defn run [options]
  (let [concourse-options (merge options
                                 (parse-options (:action-args options)))
        config (builds/config-for (:concourse-target concourse-options))]
    (sync/sync-builds-v2 (assoc concourse-options :base-url (:base-url config))
                         #(builds/concourse-builds config))))
