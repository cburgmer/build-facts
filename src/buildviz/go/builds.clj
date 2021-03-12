(ns buildviz.go.builds
  (:require [buildviz.go.api :as goapi]
            [buildviz.go.transform :as transform]
            [clj-time
             [coerce :as tc]
             [core :as t]]))

(defn- build-for-job [go-url stage-instance {:keys [name id]}]
  (let [job-instance (assoc stage-instance :job-name name)]
    (-> (goapi/build-for go-url id)
        (assoc :junit-xml (goapi/get-junit-xml go-url job-instance)))))

(defn- add-job-instances-for-stage-run [go-url stage-instance]
  (let [jobs (:jobs stage-instance)]
    (assoc stage-instance
           :job-instances (map #(build-for-job go-url stage-instance %) jobs))))


(defn- parse-stage-instance [{pipeline-run :pipeline_counter
                              stage-run :counter
                              result :result
                              jobs :jobs}]
  {:stage-run stage-run
   :pipeline-run pipeline-run
   :finished (not= "Unknown" result)
   :scheduled-time (tc/from-long (apply min (map :scheduled_date jobs)))
   :jobs jobs})

(defn- stage-instances-from [go-url sync-start-time {stage-name :stage pipeline-name :pipeline}]
  (let [safe-build-start-date (t/minus sync-start-time (t/millis 1))]
    (->> (goapi/get-stage-history go-url pipeline-name stage-name)
         (map parse-stage-instance)
         (map #(assoc % :stage-name stage-name :pipeline-name pipeline-name))
         (take-while #(t/after? (:scheduled-time %) safe-build-start-date)))))


(defn- add-pipeline-instance-for-stage-run [go-url {:keys [pipeline-run pipeline-name] :as stage-instance}]
  (let [pipeline-instance (goapi/get-pipeline-instance go-url pipeline-name pipeline-run)]
    (assoc stage-instance
           :pipeline-instance pipeline-instance)))


(defn- select-pipelines [selected-groups pipelines]
  (if (seq selected-groups)
    (let [selected-groups-set (set selected-groups)]
      (filter #(contains? selected-groups-set (:group %)) pipelines))
    pipelines))

(defn- pipeline->stages [{pipeline-name :name stages :stages}]
  (->> stages
       (map :name)
       (map #(assoc {}
                    :stage %
                    :pipeline pipeline-name))))

;; run

(defn gocd-builds [{base-url :base-url pipeline-groups :pipeline-groups}
                   sync-start-time]
  (->> (goapi/get-pipelines base-url)
       (select-pipelines pipeline-groups)
       (mapcat pipeline->stages)
       (mapcat #(stage-instances-from base-url sync-start-time %))
       (sort-by :scheduled-time)
       (take-while :finished)
       (map #(add-pipeline-instance-for-stage-run base-url %))
       (map #(add-job-instances-for-stage-run base-url %))
       (map transform/stage-instances->builds)))
