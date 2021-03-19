(ns build-facts.gocd.builds
  (:require [build-facts.gocd.api :as api]
            [build-facts.gocd.transform :as transform]))


(defn- parse-stage-instance [{pipeline-run :pipeline_counter
                              stage-run :counter
                              jobs :jobs}]
  {:stage-run stage-run
   :pipeline-run pipeline-run
   :jobs jobs})

(defn- add-pipeline-instance-for-stage-run [go-url {:keys [pipeline-run pipeline-name] :as stage-instance}]
  (let [pipeline-instance (api/get-pipeline-instance go-url pipeline-name pipeline-run)]
    (assoc stage-instance
           :pipeline-instance pipeline-instance)))

(defn- build-for-job [go-url stage-instance {:keys [name id]}]
  (let [job-instance (assoc stage-instance :job-name name)]
    (-> (api/build-for go-url id)
        (assoc :junit-xml (api/get-junit-xml go-url job-instance)))))

(defn- add-job-instances-for-stage-run [go-url stage-instance]
  (let [jobs (:jobs stage-instance)]
    (assoc stage-instance
           :job-instances (map #(build-for-job go-url stage-instance %) jobs))))

(defn- stage-instances-from [go-url {stage-name :stage pipeline-name :pipeline}]
  [(transform/get-job-name pipeline-name stage-name)
   (->> (api/get-stage-history go-url pipeline-name stage-name)
        (map parse-stage-instance)
        (map #(assoc % :stage-name stage-name :pipeline-name pipeline-name))
        (map #(add-pipeline-instance-for-stage-run go-url %))
        (map #(add-job-instances-for-stage-run go-url %))
        (map transform/stage-instances->builds))])

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


(defn gocd-builds [{base-url :base-url pipeline-groups :pipeline-groups}]
  (->> (api/get-pipelines base-url)
       (select-pipelines pipeline-groups)
       (mapcat pipeline->stages)
       (map #(stage-instances-from base-url %))))
