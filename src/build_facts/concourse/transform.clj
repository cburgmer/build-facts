(ns build-facts.concourse.transform)

(defn full-job-name [pipeline_name job_name]
  (format "%s %s" pipeline_name job_name))

(defn- unix-time-in-ms [timestamp]
  (when timestamp
    (* timestamp 1000)))

(defn- build-from [{:keys [pipeline_name job_name name status start_time end_time]}]
  {:job-name (full-job-name pipeline_name job_name)
   :build-id name
   :outcome (case status
              "succeeded" "pass"
              "failed" "fail"
              "aborted" "fail"
              "errored" "fail"
              "ongoing")
   :start (unix-time-in-ms (or start_time
                               end_time))
   :end (unix-time-in-ms end_time)})

(defn- concourse-input->input [{input-name :name version :version}]
  (let [sorted-version (into (sorted-map) version)]
    {:source-id (format "%s[%s]" input-name (->> (keys sorted-version)
                                                 (map name)
                                                 (clojure.string/join ",")))
     :revision (->> (vals sorted-version)
                    (map #(clojure.string/escape % {\, "%2C" \% "%25"}))
                    (clojure.string/join ","))}))

(defn- inputs-from [{inputs :inputs}]
  (map concourse-input->input inputs))

(defn- tasks-from [plan]
  (->> plan
       (map (fn [entry] (if (:get entry)
                          {:name (:name (:get entry))}
                          (if (:on_success entry)
                            {:name (:name (:put (:step (:on_success entry))))}
                            {:name (:name (:task entry))}))))))

(defn concourse->build [{:keys [build resources plan]}]
  (let [inputs (seq (inputs-from resources))
        tasks (seq (tasks-from plan))]
    (cond-> (build-from build)
      inputs (assoc :inputs inputs)
      tasks (assoc :tasks tasks))))
