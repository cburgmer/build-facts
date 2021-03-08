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

(defn- a-project-with-sub-projects [id & sub-projects]
  [(format "http://teamcity:8000/httpAuth/app/rest/projects/%s" id)
   (successful-json-response {:projects {:project sub-projects}
                              :buildTypes {:buildType []}})])

(defn- a-sub-project [id]
  {:id id})

(defn- a-job-with-builds [job-id & builds]
  [(format "http://teamcity:8000/httpAuth/app/rest/buildTypes/id:%s/builds/?locator=count:100,start:0&fields=build(id,number,status,startDate,finishDate,state,revisions%%28revision%%28version%%2Cvcs-root-instance%%29%%29,snapshot-dependencies%%28build%%28number%%2CbuildType%%28name%%2CprojectName%%29%%29%%29,triggered)"
           job-id)
   (successful-json-response {:build (map #(merge {:revisions []
                                                   :status "SUCCESS"
                                                   :state "finished"}
                                                  %) builds)})])

(defn- some-test-occurences [job-id build-id & tests]
  [(format "http://teamcity:8000/httpAuth/app/rest/testOccurrences?locator=count:10000,start:0,build:(id:%s)"
           build-id)
   (successful-json-response {:testOccurrence tests})])

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
  (testing "should sync a build"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation (serve-up (a-project "the_project" (a-job "theJobId" "theProject" "theJob #1"))
                                                    (a-job-with-builds "theJobId" {:id 42
                                                                                   :number 2
                                                                                   :status "SUCCESS"
                                                                                   :startDate "20160410T041049+0000"
                                                                                   :finishDate "20160410T041100+0000"})
                                                    (no-test-occurences "theJobId" 42))
        (with-out-str (sut/sync-jobs (url/url "http://teamcity:8000") data-dir ["the_project"] beginning-of-2016 nil))
        (is (= {:start 1460261449000
                :end 1460261460000
                :outcome "pass"}
               (j/parse-string (slurp (io/file data-dir "theProject theJob #1/2.json")) true))))))

  (testing "should sync in ascending order by date"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation (serve-up (a-project "the_project"
                                                               (a-job "jobId1" "theProject" "job1")
                                                               (a-job "jobId2" "theProject" "job2"))
                                                    (a-job-with-builds "jobId1"
                                                                       {:id 12
                                                                        :number 11
                                                                        :startDate "20160410T000300+0000"
                                                                        :finishDate "20160410T000400+0000"}
                                                                       {:id 10
                                                                        :number 10
                                                                        :startDate "20160410T000000+0000"
                                                                        :finishDate "20160410T000100+0000"})
                                                    (no-test-occurences "jobId1" 12)
                                                    (no-test-occurences "jobId1" 10)
                                                    (a-job-with-builds "jobId2" {:id 20
                                                                                 :number 42
                                                                                 :startDate "20160410T000100+0000"
                                                                                 :finishDate "20160410T000200+0000"})
                                                    (no-test-occurences "jobId2" 20))
        (with-out-str (sut/sync-jobs (url/url "http://teamcity:8000") data-dir ["the_project"] beginning-of-2016 nil))
        (is (= ["10.json"
                "11.json"]
               (->> (.listFiles (io/file data-dir "theProject job1"))
                    (map #(.getName %))
                    sort)))
        (is (= ["42.json"]
               (->> (.listFiles (io/file data-dir "theProject job2"))
                    (map #(.getName %))))))))

  (testing "should stop at running build"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
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
                                                                        :state "running"
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
        (is (= ["10.json"]
               (->> (.listFiles (io/file data-dir "theProject job1"))
                    (map #(.getName %))))))))

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
                    sort))))))

  (testing "should sync from given start date"
    (let [build-start (t/from-time-zone (t/date-time 2016 4 10 0 2 0) t/utc)
          data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation (serve-up (a-project "the_project"
                                                               (a-job "jobId1" "theProject" "job1"))
                                                    (a-job-with-builds "jobId1"
                                                                       {:id 12
                                                                        :number 12
                                                                        :startDate "20160410T000400+0000"
                                                                        :finishDate "20160410T000500+0000"}
                                                                       {:id 11
                                                                        :number 11
                                                                        :startDate "20160410T000200+0000"
                                                                        :finishDate "20160410T000300+0000"}
                                                                       {:id 10
                                                                        :number 10
                                                                        :startDate "20160410T000000+0000"
                                                                        :finishDate "20160410T000100+0000"})
                                                    (no-test-occurences "jobId1" 12)
                                                    (no-test-occurences "jobId1" 11)
                                                    (no-test-occurences "jobId1" 10))
        (with-out-str (sut/sync-jobs (url/url "http://teamcity:8000") data-dir ["the_project"] beginning-of-2016 build-start))
        (is (= ["11.json"
                "12.json"]
               (->> (.listFiles (io/file data-dir "theProject job1"))
                    (map #(.getName %))
                    sort))))))

  (testing "should sync test results"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation (serve-up (a-project "the_project"
                                                               (a-job "jobId1" "theProject" "job1"))
                                                    (a-job-with-builds "jobId1"
                                                                      {:id 10
                                                                       :number 10
                                                                       :startDate "20160410T000000+0000"
                                                                       :finishDate "20160410T000100+0000"})
                                                    (some-test-occurences "jobId1"
                                                                          10
                                                                          {:name "suite: class.the test"
                                                                           :status "SUCCESS"
                                                                           :duration 42}))
        (with-out-str (sut/sync-jobs (url/url "http://teamcity:8000") data-dir ["the_project"] beginning-of-2016 nil)))
      (is (= [{:name "suite"
               :children [{:name "the test"
                           :classname "class"
                           :status "pass"
                           :runtime 42}]}]
             (j/parse-string (slurp (io/file data-dir "theProject job1/10.xml")) true)))))

  (testing "should sync a sub project"
    (let [data-dir (create-tmp-dir "test-sync-jobs")]
      (fake/with-fake-routes-in-isolation
        (serve-up (a-project-with-sub-projects "the_project"
                                               (a-sub-project "the_sub_project"))
                  (a-project "the_sub_project"
                             (a-job "jobId1" "The Sub Project" "job1"))
                  (a-job-with-builds "jobId1"
                                     {:id 10
                                      :number 10
                                      :status "SUCCESS"
                                      :startDate "20160410T041049+0000"
                                      :finishDate "20160410T041100+0000"})
                  (no-test-occurences "jobId1" 10))
        (with-out-str (sut/sync-jobs (url/url "http://teamcity:8000")
                                     data-dir
                                     ["the_project"]
                                     beginning-of-2016
                                     nil))
        (is (= ["10.json"]
               (->> (.listFiles (io/file data-dir "The Sub Project job1"))
                    (map #(.getName %)))))))))
