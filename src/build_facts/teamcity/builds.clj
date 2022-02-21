(ns build-facts.teamcity.builds
  (:require [build-facts.teamcity
             [api :as api]
             [transform :as transform]]))


(defn- add-test-results [config build]
  (assoc build :tests (api/get-test-report config (:id (:build build)))))

(defn- all-builds-for-job [config {:keys [id projectName name]}]
  [(transform/full-job-name projectName name)
   (->> (api/get-builds config id)
        (map (fn [build]
               {:build build
                :project-name projectName
                :job-name name}))
        (map #(add-test-results config %))
        (map transform/teamcity-build->build-facts-build))])


(defn teamcity-builds [config]
  (->> (:projects config)
       (mapcat #(api/get-jobs config %))
       (map #(all-builds-for-job config %))))
