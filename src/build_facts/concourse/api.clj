(ns build-facts.concourse.api
  (:require [build-facts.concourse.sse :as sse]
            [cheshire.core :as j]
            [clj-http.client :as client]
            [clj-http.conn-mgr :as conn]
            [uritemplate-clj.core :as templ]
            [clojure.tools.logging :as log]))

(defn- get-json [relative-url {:keys [base-url bearer-token]}]
  (log/info (format "Retrieving %s" relative-url))
  (let [response (client/get (str base-url relative-url)
                             {:accept "application/json"
                              :headers {"User-Agent" "build-facts (https://github.com/cburgmer/build-facts)"
                                        "Authorization" (format "Bearer %s" bearer-token)}
                              :cookie-policy :standard})]
    (log/info (format "Retrieved %s: %s" relative-url (:status response)))
    (j/parse-string (:body response) true)))


(defn test-login [config]
  (try
    (get-json "/api/v1/user" config)
    (catch Exception e
      (let [help-message (format "Please run 'fly login --target %s'." (:concourse-target config))]
        (if-let [data (ex-data e)]
          (throw (Exception. (format "Login status returned not OK (%s): %s. %s"
                                     (:status data)
                                     (:body data)
                                     help-message)))
          (throw (Exception. (format "Login status returned not OK: %s. %s"
                                     e
                                     help-message))))))))

(defn all-jobs [config]
  (get-json "/api/v1/jobs" config))

(defn- builds-for-job [config {:keys [team_name pipeline_name name] :as job} up-to-id]
  (let [builds (get-json (templ/uritemplate "/api/v1/teams/{team_name}/pipelines/{pipeline_name}/jobs/{job_name}/builds?to={id}"
                                            {"team_name" team_name
                                             "pipeline_name" pipeline_name
                                             "job_name" name
                                             "id" up-to-id})
                         config)]
    (lazy-cat builds
              (when (> (count builds) 1)
                (let [last-build-id (:id (last builds))]
                  (remove #(= (:id %)
                              last-build-id)
                          (builds-for-job config job last-build-id)))))))

(defn all-builds-for-job [config job]
  (builds-for-job config job ""))

(defn build-resources [config build-id]
  (get-json (templ/uritemplate "/api/v1/builds/{id}/resources"
                               {"id" build-id})
            config))

(defn build-plan [config build-id]
  (let [json (get-json (templ/uritemplate "/api/v1/builds/{id}/plan"
                                          {"id" build-id})
                       config)]
    (:do (:plan json))))

(defn build-events [{:keys [base-url bearer-token]} build-id]
  (let [relative-url (templ/uritemplate "/api/v1/builds/{id}/events" {"id" build-id})]
    (log/info (format "Retrieving %s" relative-url))
    (let [cm (conn/make-regular-conn-manager {})
          response (client/get (str base-url relative-url)
                               {:headers {"User-Agent" "build-facts (https://github.com/cburgmer/build-facts)"
                                          "Authorization" (format "Bearer %s" bearer-token)}
                                :cookie-policy :standard
                                :as :stream
                                :connection-manager cm})
          event-stream ^InputStream (:body response)
          events (sse/load-events event-stream)]
      (log/info (format "Retrieved %s: %s" relative-url (:status response)))
      ;; Work around that `(.close event-stream)` seems blocking, so close via shutdown
      ;; Looks like we are just side-stepping the closing of the CloseableHttpResponse:
      ;; https://github.com/dakrone/clj-http/blob/dd15359451645f677b3e294164cf70330b92241d/src/clj_http/core.clj#L456
      (.shutdown cm)
      events)))
