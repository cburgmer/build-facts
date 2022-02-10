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
  (->> builds-with-input
       (filter #(= triggering-job-name (:job_name %)))
       first))

(defn- triggering-build [config {:keys [team_name pipeline_name] :as job} resources {:keys [name passed]}]
  (let [build-inputs (:inputs resources)
        build-input (->> build-inputs
                         (filter #(= name (:name %)))
                         first)
        version (:version build-input)
        input-versions (api/input-versions config team_name pipeline_name name)
        input-version (->> input-versions
                           (filter #(= version (:version %)))
                           first)
        builds-with-input (api/input-to config team_name pipeline_name name (:id input-version))]
    (first (map #(triggering-build-in-builds-with-same-resource-version % builds-with-input) passed))))

(defn- with-build-info [config job {:keys [id] :as build}]
  (let [plan (delay (api/build-plan config id))
        resources (delay (api/build-resources config id))]
    {:build build
     :resources (delay @resources)
     :plan plan
     :events (delay (when @plan
                      (api/build-events config id)))
     :triggered-by (delay (map #(triggering-build config job @resources %)
                               (:inputs job)))}))

(defn unchunk [s]
  (when (seq s)
    (cons (first s)
          (lazy-seq (unchunk (next s))))))

(defn- builds-for-job [config {:keys [pipeline_name name] :as job}]
  [(transform/full-job-name pipeline_name name)
   (lazy-seq ; don't do an api call yet, helps the progress bar to render early
    (->> (api/all-builds-for-job config job)
         unchunk                              ; avoid triggering too many resource requests due to map's chunking for vectors
         (map #(with-build-info config job %))
         (map transform/concourse->build)))])

(defn concourse-builds [config]
  (api/test-login config)
  (->> (api/all-jobs config)
       (filter #(= (:team_name %) (:team-name config)))
       (map #(builds-for-job config %))))
