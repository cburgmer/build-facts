(ns buildviz.concourse.api
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clj-yaml.core :as yaml]
            [clj-http.client :as client]))

(defn- config-for [concourse-target]
  (let [flyrc (io/file (System/getProperty "user.home") ".flyrc")
        config (-> (slurp flyrc)
                   (yaml/parse-string :keywords false)
                   (get "targets")
                   (get concourse-target))]
    (when (= (-> config
                 (get "token")
                 (get "type"))
             "bearer")
      {:base-url (get config "api")
       :bearer-token (-> config
                         (get "token")
                         (get "value"))})))

(defn- fetch-user [{:keys [base-url bearer-token]}]
  (client/get (string/join [base-url "/api/v1/user"])
              {:accept "application/json"
               :headers {"User-Agent" "buildviz (https://github.com/cburgmer/buildviz)"
                         "Authorization" (format "Bearer %s" bearer-token)}}))

(defn test-login [concourse-target]
  (let [config (config-for concourse-target)]
    (if config
      (try
        (fetch-user config)
        (catch Exception e
          (let [help-message (format "Please run 'fly login --target %s'." concourse-target)]
            (if-let [data (ex-data e)]
              (throw (Exception. (format "Login status returned not OK (%s): %s. %s"
                                         (:status data)
                                         (:body data)
                                         help-message)))
              (throw (Exception. (format "Login status returned not OK: %s. %s"
                                         e
                                         help-message)))))))
      (throw (Exception.
              (format "No token found for concourse target '%s'. Please run 'fly login --target %s -c CONCOURSE_URL' or provide a correct target."
                      concourse-target
                      concourse-target))))))
