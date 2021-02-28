(ns buildviz.concourse.api
  (:require [cheshire.core :as j]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clj-yaml.core :as yaml]
            [clj-http.client :as client]
            [uritemplate-clj.core :as templ]
            [clojure.tools.logging :as log]))

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
       :bearer-token (-> config
                         (get "token")
                         (get "value"))
       :concourse-target concourse-target}
      (throw (Exception.
              (format "No token found for concourse target '%s'. Please run 'fly login --target %s -c CONCOURSE_URL' or provide a correct target."
                      concourse-target
                      concourse-target))))))

(defn- get-json [relative-url {:keys [base-url bearer-token]}]
  (log/info (format "Retrieving %s" relative-url))
  (let [response (client/get (string/join [base-url relative-url])
                             {:accept "application/json"
                              :headers {"User-Agent" "buildviz (https://github.com/cburgmer/buildviz)"
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

(defn all-builds-for-job [config {:keys [team_name pipeline_name name] :as job}]
  (get-json (templ/uritemplate "/api/v1/teams/{team_name}/pipelines/{pipeline_name}/jobs/{job_name}/builds"
                               {"team_name" team_name
                                "pipeline_name" pipeline_name
                                "job_name" name})
            config))
