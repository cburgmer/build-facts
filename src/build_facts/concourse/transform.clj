(ns build-facts.concourse.transform
  (:require [cheshire.core :as j]))

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

(defn- event-summary-reducer [summary event]
  (let [data (j/parse-string (:data event) true)
        {{{id :id} :origin time :time selected-worker :selected_worker} :data} data]
    (if id
      (cond-> (update summary id merge {})
        time (update-in [id :first-event-time] (fn [first-event-time t] (or first-event-time t)) time)
        time (assoc-in [id :last-event-time] time)
        (= "selected-worker" (:event data)) (assoc-in [id :selected-worker] selected-worker))
      summary)))

(defn- extract-step [step type]
  {:id (:id step) :name (:name (get step type)) :type type})

(defn- extract-steps [entry]
  (cond
    (:do entry) (mapcat extract-steps (:do entry))
    (:in_parallel entry) (->> (:steps (:in_parallel entry)) (mapcat extract-steps))
    (:on_success entry) (let [{{step :step do :do on_success :on_success} :on_success} entry]
                          (if do
                            (concat (mapcat extract-steps do) (extract-steps on_success))
                            (concat (extract-steps step) (extract-steps on_success))))
    (:on_failure entry) (let [{{step :step do :do on_failure :on_failure} :on_failure} entry]
                          (if do
                            (concat (mapcat extract-steps do) (extract-steps on_failure))
                            (concat (extract-steps step) (extract-steps on_failure))))
    (:get entry) [(extract-step entry :get)]
    (:put entry) [(extract-step entry :put)]
    (:set_pipeline entry) [(extract-step entry :set_pipeline)]
    (:retry entry) (mapcat extract-steps (:retry entry))
    :else [(extract-step entry :task)]))

(defn- unique-step-names [steps]
  (->> (group-by :name steps)
       (mapcat (fn [[_ same-name-steps]] (if (= 1 (count same-name-steps))
                                           same-name-steps
                                           (map-indexed (fn [idx step]
                                                          (assoc step :name (format "%s #%s" (:name step) (+ idx 1)))) same-name-steps))))))

(defn- step-names-with-step-type [steps]
  (map (fn [step] (assoc step :name (format "%s (%s)" (:name step) (name (:type step)))))
       steps))

(defn- tasks-from [plan events]
  (let [event-summary (reduce event-summary-reducer {} events)]
    (->> plan
         extract-steps
         unique-step-names
         step-names-with-step-type
         (filter (fn [{id :id}] (get event-summary id)))
         (map (fn [{id :id name :name}]
                (let [{:keys [first-event-time last-event-time selected-worker]} (get event-summary id)]
                  (cond-> {:name name
                           :start (unix-time-in-ms first-event-time)
                           :end (unix-time-in-ms last-event-time)}
                    selected-worker (assoc :worker selected-worker))))))))

(defn concourse->build [{:keys [build resources plan events]}]
  (let [the-build (build-from build)]
    (if (= "ongoing" (:outcome the-build))
      the-build
      (let [inputs (not-empty (inputs-from @resources))
            tasks (not-empty (tasks-from @plan @events))]
        (cond-> the-build
          inputs (assoc :inputs inputs)
          tasks (assoc :tasks tasks))))))
