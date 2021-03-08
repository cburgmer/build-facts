(ns buildviz.teamcity.sync-jobs-test
  (:require [buildviz.teamcity.sync-jobs :as sut]
            [buildviz.util.url :as url]
            [cheshire.core :as j]
            [clj-http.fake :as fake]
            [clj-time
             [coerce :as tc]
             [core :as t]]
            [clojure.java.io :as io]
            [clojure.test :refer :all]))

(defn- successful-json-response [body]
  (fn [_] {:status 200
           :body (j/generate-string body)}))

(defn- a-job [id project-name job-name]
  {:id id
   :projectName project-name
   :name job-name})

(defn- a-project [id & jobs]
  [(format "http://teamcity:8000/httpAuth/app/rest/projects/%s" id)
   (successful-json-response {:buildTypes {:buildType jobs}})])

(defn- a-sub-project [id]
  {:id id})

(defn- a-job-with-builds [job-id & builds]
  [(format "http://teamcity:8000/httpAuth/app/rest/buildTypes/id:%s/builds/?locator=count:100,start:0&fields=build(id,number,status,startDate,finishDate,state,revisions%%28revision%%28version%%2Cvcs-root-instance%%29%%29,snapshot-dependencies%%28build%%28number%%2CbuildType%%28name%%2CprojectName%%29%%29%%29,triggered)"
           job-id)
   (successful-json-response {:build (map #(merge {:revisions []
                                                   :status "SUCCESS"
                                                   :state "finished"}
                                                  %) builds)})])

(defn- no-test-occurences [job-id build-id & tests]
  [(format "http://teamcity:8000/httpAuth/app/rest/testOccurrences?locator=count:10000,start:0,build:(id:%s)"
           build-id)
   (successful-json-response {:testOccurrence []})])

(def beginning-of-2016 (t/date-time 2016 1 1))

(defn- serve-up [& routes]
  (into {} routes))

(defn- create-tmp-dir [prefix] ; http://stackoverflow.com/questions/617414/create-a-temporary-directory-in-java
  (let [tmp-file (java.io.File/createTempFile prefix ".tmp")]
    (.delete tmp-file)
    (.getPath tmp-file)))


(deftest test-teamcity-sync-jobs
  (testing "should resume where left off"
    (let [latest-build-start (t/from-time-zone (t/date-time 2016 4 10 0 2 0) t/utc)
          data-dir (create-tmp-dir "test-sync-jobs")]
      (.mkdirs (io/file data-dir "theProject job1"))
      (spit (io/file data-dir "theProject job1" "old_run.json")
            (j/generate-string {:start (tc/to-long latest-build-start)}))
      (fake/with-fake-routes-in-isolation (serve-up (a-project "the_project"
                                                               (a-job "jobId1" "theProject" "job1"))
                                                    (a-job-with-builds "jobId1"
                                                                       {:id 12
                                                                        :number 12
                                                                        :state "finished"
                                                                        :startDate "20160410T000400+0000"
                                                                        :finishDate "20160410T000500+0000"}
                                                                       {:id 11
                                                                        :number 11
                                                                        :state "finished"
                                                                        :startDate "20160410T000200+0000"
                                                                        :finishDate "20160410T000300+0000"}
                                                                       {:id 10
                                                                        :number 10
                                                                        :state "finished"
                                                                        :startDate "20160410T000000+0000"
                                                                        :finishDate "20160410T000100+0000"})
                                                    (no-test-occurences "jobId1" 12)
                                                    (no-test-occurences "jobId1" 11)
                                                    (no-test-occurences "jobId1" 10))
        (with-out-str (sut/sync-jobs (url/url "http://teamcity:8000") data-dir ["the_project"] beginning-of-2016 nil))
        (is (= ["11.json"
                "12.json"
                "old_run.json"]
               (->> (.listFiles (io/file data-dir "theProject job1"))
                    (map #(.getName %))
                    sort)))))))
