(ns build-facts.jenkins.builds
  (:require [build-facts.jenkins
             [api :as api]
             [transform :as transform]]
            [clj-time
             [coerce :as tc]
             [core :as t]]))

(defn add-test-results [jenkins-url {:keys [job-name number] :as build}]
  (assoc build :test-report (api/get-test-report jenkins-url job-name number)))

(defn builds-for-job [base-url job-name]
  [job-name (->> (api/get-builds base-url job-name)
                 (map (partial add-test-results base-url))
                 (map transform/jenkins-build->build-facts-build))])

(defn jenkins-builds [{base-url :base-url}]
  (->> (api/get-jobs base-url)
       (map #(builds-for-job base-url %))))
