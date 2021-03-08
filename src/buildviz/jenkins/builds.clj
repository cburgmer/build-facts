(ns buildviz.jenkins.builds
  (:require [buildviz.jenkins
             [api :as api]
             [transform :as transform]]
            [clj-time
             [coerce :as tc]
             [core :as t]]))

(defn add-test-results [jenkins-url {:keys [job-name number] :as build}]
  (assoc build :test-report (api/get-test-report jenkins-url job-name number)))

(defn- jenkins-build->start-time [{timestamp :timestamp}]
  (tc/from-long timestamp))

(defn- ignore-ongoing-builds [builds]
  (filter :result builds))

(defn- all-builds-for-job [jenkins-url sync-start-time job-name]
  (->> (api/get-builds jenkins-url job-name)
       (take-while #(t/after? (jenkins-build->start-time %) sync-start-time))
       ignore-ongoing-builds))

(defn jenkins-builds [{base-url :base-url} sync-start-time]
  (->> (api/get-jobs base-url)
       (mapcat #(all-builds-for-job base-url sync-start-time %))
       (map (partial add-test-results base-url))
       (map transform/jenkins-build->buildviz-build)))
