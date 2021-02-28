(ns buildviz.concourse.transform)

(defn- full-job-name [pipeline_name job_name]
  (format "%s %s" pipeline_name job_name))

(defn concourse->build [{:keys [pipeline_name job_name name status start_time end_time]}]
  {:job-name (full-job-name pipeline_name job_name)
   :build-id name
   :build {:outcome (if (= status "succeeded")
                      "pass"
                      "fail")
           :start (* start_time 1000)
           :end (* end_time 1000)}})
