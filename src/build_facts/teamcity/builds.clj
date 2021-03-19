(ns build-facts.teamcity.builds
  (:require [build-facts.teamcity
             [api :as api]
             [transform :as transform]]))

(defn- add-test-results [teamcity-url build]
  (assoc build :tests (api/get-test-report teamcity-url (:id (:build build)))))

(defn- all-builds-for-job [base-url {:keys [id projectName name]}]
  [(transform/full-job-name projectName name)
   (->> (api/get-builds base-url id)
        (map (fn [build]
               {:build build
                :project-name projectName
                :job-name name}))
        (map #(add-test-results base-url %))
        (map transform/teamcity-build->build-facts-build))])


(defn teamcity-builds [{base-url :base-url projects :projects}]
  (->> projects
       (mapcat #(api/get-jobs base-url %))
       (map #(all-builds-for-job base-url %))))
