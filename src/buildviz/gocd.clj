(ns buildviz.gocd
  (:require [buildviz.shared :as shared]
            [buildviz.sync :as sync]
            [buildviz.go.builds :as builds]
            [buildviz.util.url :as url]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]))

(def gocd-cli-options
  (concat
   shared/cli-options
   [["-g" "--pipeline-group PIPELINE_GROUP" "Go pipeline groups to be synced, all by default"
     :id :pipeline-group-names
     :default nil
     :assoc-fn (fn [previous key val] (assoc previous key (conj (get previous key) val)))]]))

(defn- gocd-usage [options-summary]
  (string/join "\n"
               ["Syncs GoCD build history"
                ""
                "Usage: buildviz.main [OPTIONS] GO_URL"
                ""
                "Options:"
                options-summary
                ""
                "Action arguments:"
                ""
                "GO_URL            The URL of the GoCD installation"]))

(defn- parse-options [c-args]
  (let [args (parse-opts c-args gocd-cli-options)]
    (when (:help (:options args))
      (println (gocd-usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [base-url (first (:arguments args))]
      (shared/assert-parameter #(some? base-url) "The URL for GoCD is required. Try --help.")

      (merge (:options args)
             {:base-url (url/url base-url)
              :pipeline-group-names (set (:pipeline-group-names (:options args)))}))))

(defn run [options]
  (let [gocd-options (merge options
                            (parse-options (:action-args options)))]
    (sync/sync-builds gocd-options
                      #(builds/gocd-builds gocd-options %))))
