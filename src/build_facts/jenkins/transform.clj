(ns build-facts.jenkins.transform)

(defn- jenkins-test-case->build-facts-test-case [{:keys [className name duration status]}]
  {:classname className
   :name name
   :runtime (Math/round (* duration 1000))
   :status (case status
             "PASSED" "pass"
             "FIXED" "pass"
             "REGRESSION" "fail"
             "FAILED" "fail"
             "SKIPPED" "skipped")})

(defn- jenkins-suite->build-facts-suite [{:keys [name cases]}]
  {:name name
   :children (map jenkins-test-case->build-facts-test-case cases)})

(defn- convert-test-results [{test-report :test-report}]
  (when test-report
    (->> (get test-report :suites)
         (map jenkins-suite->build-facts-suite))))

(defn- git-input-from [{actions :actions}]
  (when-let [git-revision-info (first (filter :lastBuiltRevision actions))]
    [{:revision (get-in git-revision-info [:lastBuiltRevision :SHA1])
      :source-id (get-in git-revision-info [:remoteUrls 0])}]))

(defn- parameters-input-from [{actions :actions}]
  (->> (some :parameters actions)
       (map (fn [{:keys [name value]}]
              {:revision value
               :source-id name}))))

(defn- build-inputs [build]
  (seq (concat (git-input-from build)
               (parameters-input-from build))))

(defn- manually-started-by-user? [causes]
  (some #(contains? % :userId) causes))

(defn- build-triggered-by [{actions :actions}]
  (let [causes (mapcat :causes (filter :causes actions))]
    (when-not (manually-started-by-user? causes)
      (->> causes
           (filter :upstreamProject)
           (map (fn [cause]
                  {:job-name (:upstreamProject cause)
                   :build-id (.toString (:upstreamBuild cause))}))
           seq))))


(defn jenkins-build->build-facts-build [{:keys [job-name number timestamp duration result] :as build}]
  (let [inputs (build-inputs build)
        triggered-by (build-triggered-by build)
        test-results (convert-test-results build)]
    (cond-> {:job-name job-name
             :build-id (str number)
             :start timestamp
             :end (+ timestamp duration)
             :outcome (case result
                            "SUCCESS" "pass"
                            "FAILURE" "fail"
                            "ongoing")}
      inputs (assoc :inputs inputs)
      triggered-by (assoc :triggered-by triggered-by)
      test-results (assoc :test-results test-results))))
