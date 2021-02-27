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
          (if-let [data (ex-data e)]
            (throw (Exception. (format "Login status returned not OK (%s): %s" (:status data) (:body data))))
            (throw (Exception. (format "Login status returned not OK: %s" e))))))
      (throw (Exception. (format "No token found for concourse target '%s'" concourse-target))))))
