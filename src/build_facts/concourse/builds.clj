(ns build-facts.concourse.builds
  (:require [build-facts.concourse
             [api :as api]
             [transform :as transform]]
            [clojure.tools.logging :as log]))


(defn- triggering-build-in-builds-with-same-resource-version [triggering-job-name builds-with-input]
  (let [candidates (->> builds-with-input
                        (filter #(= triggering-job-name (:job_name %)))
                        (filter #(= "succeeded" (:status %)))
                        (sort-by :end_time))]
    (first candidates)))

(defn- build->triggered-input-version [resources input-name]
  (->> (:inputs resources)
       (filter #(= input-name (:name %)))
       (filter #(= true (:first_occurrence %)))
       first
       :version))

(defn- triggering-build [resources {:keys [input-name from-previous-jobs versions-with-context]}]
  (when-let [input-version (build->triggered-input-version resources input-name)]
    (if-let [version-with-context (->> versions-with-context
                                    (filter #(= input-version (:version %)))
                                    first)]
      (let [builds-with-input (concat @(:input-to version-with-context)
                                      @(:output-of version-with-context))]
        (map #(triggering-build-in-builds-with-same-resource-version % builds-with-input) from-previous-jobs))
      (log/warnf "No triggered-by information could be deduced as version history did not contain the relevant version %s" (prn-str input-version)))))

(defn- with-build-info [config inputs-and-versions {:keys [id] :as build}]
  (let [plan (delay (api/build-plan config id))
        resources (delay (api/build-resources config id))]
    {:build build
     :resources (delay @resources)
     :plan plan
     :events (delay (when @plan
                      (api/build-events config id)))
     :triggered-by (delay (->> inputs-and-versions
                               (mapcat #(triggering-build @resources %))))}))

(defn- aggregate-input-versions [config team_name pipeline_name input_name]
  (->> (api/input-versions config team_name pipeline_name input_name)
       (map (fn [{:keys [id version]}] {:version version
                                        :input-to (delay (api/input-to config team_name pipeline_name input_name id))
                                        :output-of (delay (api/output-of config team_name pipeline_name input_name id))}))))

(defn- job->inputs-and-versions [config {:keys [team_name pipeline_name inputs]}]
  (->> inputs
       (filter #(:passed %))
       (filter #(:trigger %))
       (map (fn [input] {:input-name (:name input)
                    :from-previous-jobs (:passed input)
                    :versions-with-context (aggregate-input-versions config team_name pipeline_name (:name input))}))))

(defn unchunk [s]
  (when (seq s)
    (cons (first s)
          (lazy-seq (unchunk (next s))))))

(defn- builds-for-job [config {:keys [pipeline_name name] :as job}]
  [(transform/full-job-name pipeline_name name)
   (lazy-seq ; don't do an api call yet, helps the progress bar to render early
    (let [inputs-and-versions (job->inputs-and-versions config job)]
      (->> (api/all-builds-for-job config job)
           unchunk                              ; avoid triggering too many resource requests due to map's chunking for vectors
           (map #(with-build-info config inputs-and-versions %))
           (map transform/concourse->build))))])

(defn concourse-builds [config]
  (api/test-login config)
  (->> (api/all-jobs config)
       (filter #(= (:team_name %) (:team-name config)))
       (map #(builds-for-job config %))))
