(ns build-facts.teamcity
  (:require [build-facts.shared :as shared]
            [build-facts.sync :as sync]
            [build-facts.teamcity.builds :as builds]
            [build-facts.util.url :as url]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]))

(def concourse-cli-options
  (concat
   shared/cli-options
   [["-p" "--project PROJECT" "TeamCity project to be synced"
     :id :projects
     :default []
     :assoc-fn (fn [previous key val] (assoc previous key (conj (get previous key) val)))]]))

(defn teamcity-usage [options-summary]
  (string/join "\n"
               ["Syncs TeamCity build history"
                ""
                "Usage: build-facts.main teamcity [OPTIONS] TEAMCITY_URL"
                ""
                "Options:"
                options-summary
                ""
                "Action arguments:"
                ""
                "TEAMCITY_URL           The URL of the TeamCity installation. You will most probably"
                "                       need some form of credentials. If 'guest user login' is"
                "                       enabled, you can try e.g. 'http://guest@localhost:8111'."
                ""
                "Environment variables:"
                ""
                "TEAMCITY_USER          Username for basic auth"
                "TEAMCITY_PASSWORD      Password for basic auth"]))

(defn- parse-options [c-args]
  (let [args (parse-opts c-args concourse-cli-options)]
    (when (:help (:options args))
      (println (teamcity-usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [teamcity-url (url/url (first (:arguments args)))
          projects (:projects (:options args))]

      (shared/assert-parameter #(some? teamcity-url) "The URL of TeamCity is required. Try --help.")
      (shared/assert-parameter #(not (empty? projects)) "At least one project is required. Try --help.")

      (merge (:options args)
             {:base-url teamcity-url
              :projects projects}))))

(defn config-for [base-url projects]
  {:base-url base-url
   :projects projects
   :basic-auth (when-let [teamcity-user (System/getenv "TEAMCITY_USER")]
                 [teamcity-user (System/getenv "TEAMCITY_PASSWORD")])})

(defn run [options]
  (let [teamcity-options (merge options
                                (parse-options (:action-args options)))
        config (config-for (:base-url teamcity-options) (:projects teamcity-options))]
    (sync/sync-builds teamcity-options
                      #(builds/teamcity-builds config))))
