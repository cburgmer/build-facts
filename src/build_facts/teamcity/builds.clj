(ns build-facts.teamcity.builds
  (:require [build-facts.teamcity
             [api :as api]
             [transform :as transform]]
            [clj-time
             [coerce :as tc]
             [core :as t]]))

(defn- all-builds-for-job [teamcity-url sync-start-time {:keys [id projectName name]}]
  (let [safe-build-start-time (t/minus sync-start-time (t/millis 1))]
    (->> (api/get-builds teamcity-url id)
         (map (fn [build]
                {:build build
                 :project-name projectName
                 :job-name name}))
         (take-while #(t/after? (transform/parse-build-date (get-in % [:build :startDate])) safe-build-start-time)))))

(defn- add-test-results [teamcity-url build]
  (assoc build :tests (api/get-test-report teamcity-url (:id (:build build)))))


(defn- sync-oldest-first-to-deal-with-cancellation [builds]
  (sort-by #(get-in % [:build :finishDate]) builds))

(defn- stop-at-first-non-finished-so-we-can-resume-later [builds]
  (take-while #(= "finished" (get-in % [:build :state])) builds))

(defn teamcity-builds [{base-url :base-url projects :projects} sync-start-time]
  (->> projects
       (mapcat #(api/get-jobs base-url %))
       (mapcat #(all-builds-for-job base-url sync-start-time %))
       sync-oldest-first-to-deal-with-cancellation
       stop-at-first-non-finished-so-we-can-resume-later
       (map #(add-test-results base-url %))
       (map transform/teamcity-build->build-facts-build)))
