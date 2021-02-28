(ns buildviz.concourse.sync-jobs
  (:require [buildviz.concourse
             [api :as api]
             [transform :as transform]]
            [buildviz.storage :as storage]
            [clj-progress.core :as progress]
            [clojure.tools.logging :as log]))

(defn- store [data-dir {:keys [job-name build-id build]}]
  (log/info (format "Syncing %s %s: build" job-name build-id))
  (storage/store-build! data-dir job-name build-id build))

(defn sync-jobs [concourse-target data-dir]
  (let [config (api/config-for concourse-target)]
    (println (format "Concourse %s (%s)" (:base-url config) concourse-target) )
    (api/test-login config)
    (->> (api/all-jobs config)
         (mapcat #(api/all-builds-for-job config %))
         (progress/init "Syncing")
         (map (comp progress/tick
                    (partial store data-dir)
                    transform/concourse->build))
         dorun
         (progress/done))))
