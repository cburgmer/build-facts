(ns build-facts.concourse.builds
  (:require [build-facts.concourse
             [api :as api]
             [transform :as transform]]
            [clj-yaml.core :as yaml]
            [clj-time
             [coerce :as tc]
             [core :as t]]
            [clojure.java.io :as io]))

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

(defn- builds-for-job [config {:keys [pipeline_name name] :as job}]
  [(transform/full-job-name pipeline_name name)
   (->> (api/all-builds-for-job config job)
        (map transform/concourse->build))])

(defn concourse-builds [config]
  (api/test-login config)
  (->> (api/all-jobs config)
       (filter #(= (:team_name %) (:team-name config)))
       (map #(builds-for-job config %))))
