(ns build-facts.teamcity.api
  (:require [build-facts.util.url :as url]
            [cheshire.core :as j]
            [clj-http.client :as client]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [uritemplate-clj.core :as templ]))

(defn- get-json [{:keys [base-url basic-auth]} relative-url]
  (log/info (format "Retrieving %s" relative-url))
  (let [response (client/get (string/join [(url/with-plain-text-password base-url)
                                           relative-url])
                             {:accept "application/json"
                              :headers {"User-Agent" "build-facts (https://github.com/cburgmer/build-facts)"}
                              :basic-auth basic-auth})]
    (log/info (format "Retrieved %s: %s" relative-url (:status response)))
    (j/parse-string (:body response) true)))

(defn get-jobs [config project-id]
  (let [response (get-json config (templ/uritemplate "/httpAuth/app/rest/projects{/project}"
                                                     {"project" project-id}))
        jobs (-> response
                 (get :buildTypes)
                 (get :buildType))
        sub-projects (->> response
                          :projects
                          :project
                          (map :id))]
    (concat jobs
            (mapcat #(get-jobs config %) sub-projects))))


(def ^:private builds-paging-count 100)

(def ^:private build-fields ["id" "number" "status" "startDate" "finishDate"
                             "state" "revisions(revision(version,vcs-root-instance))"
                             "snapshot-dependencies(build(number,buildType(name,projectName)))"
                             "triggered"])

(defn- get-builds-from [config job-id offset]
  (let [response (get-json config
                           (templ/uritemplate "/httpAuth/app/rest/buildTypes/id:{job}/builds/?locator=count:{count},start:{offset}&fields=build({fields})"
                                              {"job" job-id
                                               "count" builds-paging-count
                                               "offset" offset
                                               "fields" build-fields}))
        builds (get response :build)]
    (if (< (count builds) builds-paging-count)
      builds
      (let [next-offset (+ offset builds-paging-count)]
        (concat builds
                (get-builds-from config job-id next-offset))))))

(defn get-builds [config job-id]
  (lazy-seq (get-builds-from config job-id 0))) ; don't do an api call yet, helps the progress bar to render early


(def ^:private test-occurrence-paging-count 10000)

(defn- get-test-report-from [config build-id offset]
  (let [response (get-json config
                           (templ/uritemplate "/httpAuth/app/rest/testOccurrences?locator=count:{count},start:{offset},build:(id:{build})"
                                              {"count" test-occurrence-paging-count
                                               "offset" offset
                                               "build" build-id}))
        test-occurrences (get response :testOccurrence)]
    (if (< (count test-occurrences) test-occurrence-paging-count)
      test-occurrences
      (let [next-offset (+ offset test-occurrence-paging-count)]
        (concat test-occurrences
                (get-test-report-from config build-id next-offset))))))

(defn get-test-report [config build-id]
  (get-test-report-from config build-id 0))
