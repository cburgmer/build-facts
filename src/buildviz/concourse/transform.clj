(ns buildviz.concourse.transform)

(defn- full-job-name [pipeline_name job_name]
  (format "%s %s" pipeline_name job_name))

(defn- unix-time-in-ms [timestamp]
  (when timestamp
    (* timestamp 1000)))

(defn concourse->build [{:keys [pipeline_name job_name name status start_time end_time]}]
  {:job-name (full-job-name pipeline_name job_name)
   :build-id name
   :outcome (if (= status "succeeded")
              "pass"
              "fail")
   :start (unix-time-in-ms (or start_time
                               end_time))
   :end (unix-time-in-ms end_time)})
