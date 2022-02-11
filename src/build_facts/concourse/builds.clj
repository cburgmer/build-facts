(ns build-facts.concourse.builds
  (:require [build-facts.concourse
             [api :as api]
             [transform :as transform]]
            [clj-yaml.core :as yaml]
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
       :team-name (get config "team")
       :bearer-token (-> config
                         (get "token")
                         (get "value"))
       :concourse-target concourse-target}
      (throw (Exception.
              (format "No token found for concourse target '%s'. Please run 'fly login --target %s -c CONCOURSE_URL' or provide a correct target."
                      concourse-target
                      concourse-target))))))

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
    (let [version-with-context (->> versions-with-context
                                    (filter #(= input-version (:version %)))
                                    first)
          builds-with-input (concat @(:input-to version-with-context)
                                    @(:output-of version-with-context))]
      (map #(triggering-build-in-builds-with-same-resource-version % builds-with-input) from-previous-jobs))))

(defn- with-build-info [config inputs-and-versions {:keys [id] :as build}]
  (let [plan (delay (api/build-plan config id))
        resources (delay (api/build-resources config id))]
    {:build build
     :resources (delay @resources)
     :plan plan
     :events (delay (when @plan
                      (api/build-events config id)))
     :triggered-by (delay (->> inputs-and-versions
                               (filter #(:triggers-automatically? %))
                               (mapcat #(triggering-build @resources %))))}))

(defn- aggregate-input-versions [config team_name pipeline_name input_name]
  (->> (api/input-versions config team_name pipeline_name input_name)
       (map (fn [{:keys [id version]}] {:version version
                                        :input-to (delay (api/input-to config team_name pipeline_name input_name id))
                                        :output-of (delay (api/output-of config team_name pipeline_name input_name id))}))))

(defn- job->inputs-and-versions [config {:keys [team_name pipeline_name inputs]}]
  (map (fn [input] {:input-name (:name input)
                    :from-previous-jobs (:passed input)
                    :triggers-automatically? (:trigger input)
                    :versions-with-context (aggregate-input-versions config team_name pipeline_name (:name input))})
       inputs))

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
