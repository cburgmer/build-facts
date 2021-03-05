(ns buildviz.concourse.builds
  (:require [buildviz.concourse
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
       :bearer-token (-> config
                         (get "token")
                         (get "value"))
       :concourse-target concourse-target}
      (throw (Exception.
              (format "No token found for concourse target '%s'. Please run 'fly login --target %s -c CONCOURSE_URL' or provide a correct target."
                      concourse-target
                      concourse-target))))))

(defn concourse-builds [config sync-start-time]
  (api/test-login config)
  (->> (api/all-jobs config)
       (mapcat #(api/all-builds-for-job config %))
       (map transform/concourse->build)
       (take-while #(t/after? (tc/from-long (:start (:build %))) sync-start-time))))
