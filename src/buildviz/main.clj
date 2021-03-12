(ns buildviz.main
  (:gen-class)
  (:require [buildviz.shared :as shared]
            [buildviz.concourse :as concourse]
            [buildviz.gocd :as gocd]
            [buildviz.jenkins :as jenkins]
            [buildviz.teamcity :as teamcity]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]))

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
                "  gocd         Reads build data from GoCD"
                "  jenkins      Reads build data from Jenkins"
                "  teamcity     Reads build data from TeamCity"]))

(defn- parse-options [c-args]
  (let [args (parse-opts c-args shared/cli-options :in-order true)]
    (when (:help (:options args))
      (println (usage (:summary args)))
      (System/exit 0))
    (when (:errors args)
      (println (string/join "\n" (:errors args)))
      (System/exit 1))

    (let [action (first (:arguments args))]
      (shared/assert-parameter #(some? action) "The action is required. Try --help.")

      (merge (:options args)
             {:action action
              :action-args (rest (:arguments args))}))))

(defn -main [& c-args]
  (let [{:keys [action] :as options} (parse-options c-args)]

    (case action
      "concourse" (concourse/run options)
      "gocd" (gocd/run options)
      "jenkins" (jenkins/run options)
      "teamcity" (teamcity/run options)
      (do (println "Unknown action. Try --help.")
          (System/exit 1)))))
