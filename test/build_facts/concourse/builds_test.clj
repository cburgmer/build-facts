(ns build-facts.concourse.builds-test
  (:require [build-facts.concourse.builds :as sut]
            [cheshire.core :as j]
            [clj-http.fake :as fake]
            [clj-time
             [coerce :as tc]
             [core :as t]]
            [clojure.string :as string]
            [clojure.test :refer :all]))

(defn- successful-json-response [body]
  (fn [_] {:status 200
           :body (j/generate-string body)}))

(defn- a-job
  ([team-name pipeline-name job-name] {:team_name team-name
                                       :pipeline_name pipeline-name
                                       :name job-name})
  ([team-name pipeline-name job-name job] (merge (a-job team-name pipeline-name job-name)
                                                 job)))

(defn- all-jobs [& jobs]
  ["http://concourse:8000/api/v1/jobs"
   (successful-json-response jobs)])

(defn- some-builds-up-to [up-to-id team-name pipeline-name job-name & builds]
  [(format "http://concourse:8000/api/v1/teams/%s/pipelines/%s/jobs/%s/builds?to=%s"
           team-name
           pipeline-name
           job-name
           up-to-id)
   (successful-json-response (map #(assoc %
                                          :pipeline_name pipeline-name
                                          :job_name job-name)
                                  builds))])

(defn- some-builds [team-name pipeline-name job-name & builds]
  (apply some-builds-up-to (concat ["" team-name pipeline-name job-name] builds)))

(defn- some-resources [build-id & inputs]
  [(format "http://concourse:8000/api/v1/builds/%s/resources" build-id)
   (successful-json-response {:inputs inputs})])

(defn- a-task [id name]
  {:id id :task {:name name}})

(defn- a-resource-put [id name]
  {:on_success {:step {:id id :put {:name name}}}})

(defn- some-plan
  ([build-id] [(format "http://concourse:8000/api/v1/builds/%s/plan" build-id)
               (successful-json-response {:plan {:do []}})])
  ([build-id step] [(format "http://concourse:8000/api/v1/builds/%s/plan" build-id)
                    (successful-json-response {:plan step})]))

(defn- no-plan [build-id]
  [(format "http://concourse:8000/api/v1/builds/%s/plan" build-id)
   (fn [_] {:status 404})])

(defn- some-events [build-id & event-data]
  (let [events (concat (map-indexed (fn [idx data]
                                      (format "id: %s\nevent: event\ndata: %s" idx (j/generate-string data)))
                                    event-data)
                       [(format "id: %s\nevent: end\ndata" (count event-data))
                        ""])]
    [(format "http://concourse:8000/api/v1/builds/%s/events" build-id)
     (fn [_] {:status 200
              :body (string/join "\n\n" events)})]))

(defn- some-resource-versions [team-name pipeline-name resource-name & versions]
  [(format "http://concourse:8000/api/v1/teams/%s/pipelines/%s/resources/%s/versions"
           team-name pipeline-name resource-name)
   (successful-json-response versions)])

(defn- some-resource-version-input-to [team-name pipeline-name resource-name id & builds]
  [(format "http://concourse:8000/api/v1/teams/%s/pipelines/%s/resources/%s/versions/%s/input_to"
           team-name pipeline-name resource-name id)
   (successful-json-response builds)])

(defn- valid-session []
  ["http://concourse:8000/api/v1/user"
   (successful-json-response {})])


(defn- serve-up [& routes]
  (into {} routes))

(defn- unix-time-in-s [& params]
  (/ (tc/to-long (apply t/date-time params))
     1000))


(deftest test-concourse-builds
  (testing "should sync a successful build"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4)
                                                  (some-events 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "pass"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "should sync a failing build"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "failed"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4)
                                                  (some-events 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "fail"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "should handle a started build and not request any further resources"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "started"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "ongoing"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "should handle aborted build without start time"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "aborted"
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4)
                                                  (some-events 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "fail"
                  :start 1451642401000
                  :end 1451642401000}]])))))

  (testing "should handle errored build"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "errored"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4)
                                                  (some-events 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "fail"
                  :start 1451642400000,
                  :end 1451642401000}]])))))

  (testing "should not sync builds from a different team"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job")
                                                            (a-job "another-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-builds "another-team" "my-pipeline" "my-job"
                                                               {:id 10
                                                                :name "43"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4)
                                                  (some-events 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "pass"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "should handle pagination for long build histories"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 5
                                                                :name "43"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 2 1 12 0 0)
                                                                :end_time (unix-time-in-s 2016 2 1 12 0 1)}
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-builds-up-to 4
                                                                     "my-team" "my-pipeline" "my-job"
                                                                     {:id 4
                                                                      :name "42"
                                                                      :status "succeeded"
                                                                      :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                      :end_time (unix-time-in-s 2016 1 1 10 0 1)}
                                                                     {:id 2
                                                                      :name "41"
                                                                      :status "succeeded"
                                                                      :start_time (unix-time-in-s 2016 1 1 2 0 0)
                                                                      :end_time (unix-time-in-s 2016 1 1 2 0 1)})
                                                  (some-builds-up-to 2
                                                                     "my-team" "my-pipeline" "my-job"
                                                                     {:id 2
                                                                      :name "41"
                                                                      :status "succeeded"
                                                                      :start_time (unix-time-in-s 2016 1 1 2 0 0)
                                                                      :end_time (unix-time-in-s 2016 1 1 2 0 1)})
                                                  (some-resources 5)
                                                  (some-resources 4)
                                                  (some-resources 2)
                                                  (some-plan 5)
                                                  (some-plan 4)
                                                  (some-plan 2)
                                                  (some-events 5)
                                                  (some-events 4)
                                                  (some-events 2))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "43"
                  :outcome "pass"
                  :start 1454328000000
                  :end 1454328001000}
                 {:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "pass"
                  :start 1451642400000
                  :end 1451642401000}
                 {:job-name "my-pipeline my-job"
                  :build-id "41"
                  :outcome "pass"
                  :start 1451613600000
                  :end 1451613601000}]])))))

  (testing "should resolve builds lazily"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)}
                                                               {:id 3
                                                                :name "41"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 9 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 9 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4)
                                                  (some-events 4))
      (let [[[_ builds]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                :bearer-token "fake-token"
                                                :team-name "my-team"})]
        (first builds)))) ; should not error due to route for build #3 not defined

  (testing "should handle multiple jobs"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job")
                                                            (a-job "my-team" "my-pipeline" "another-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-builds "my-team" "my-pipeline" "another-job"
                                                               {:id 10
                                                                :name "10"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-resources 10)
                                                  (some-plan 4)
                                                  (some-plan 10)
                                                  (some-events 4)
                                                  (some-events 10))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "pass"
                  :start 1451642400000
                  :end 1451642401000}]]
               ["my-pipeline another-job"
                [{:job-name "my-pipeline another-job"
                  :build-id "10"
                  :outcome "pass"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "should expose inputs"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4
                                                                  {:name "git"
                                                                   :version {:ref "abcd1234"}}
                                                                  {:name "version"
                                                                   :version {:number "1113.0.0"}})
                                                  (some-plan 4)
                                                  (some-events 4))
      (let [[[_, [build]]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (= [{:revision "abcd1234" :source-id "git[ref]"}
                {:revision "1113.0.0" :source-id "version[number]"}]
               (:inputs build))))))

  (testing "should expose tasks"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4
                                                             {:do [(a-task "abcd1234" "task one")
                                                                   (a-task "defg987" "another task")]})
                                                  (some-events 4
                                                               {:data {:time 1617735351 :origin {:id "abcd1234"}}}
                                                               {:data {:time 1617735353 :origin {:id "abcd1234"}}}
                                                               {:data {:time 1617735354 :origin {:id "defg987"}}}
                                                               {:data {:time 1617735360 :origin {:id "defg987"}}}
                                                               {:data {:time 1617735361 :origin {:id "defg987"}}}))
      (let [[[_, [build]]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (= [{:name "task one (task)" :start 1617735351000 :end 1617735353000}
                {:name "another task (task)" :start 1617735354000 :end 1617735361000}]
               (:tasks build))))))

  (testing "should only report tasks which have been run"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4
                                                             {:do [(a-task "abcd1234" "a task")
                                                                   (a-resource-put "defg9876" "docker")]})
                                                  (some-events 4
                                                               {:data {:time 1617735351 :origin {:id "abcd1234"}}}))
      (let [[[_, [build]]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (= [{:name "a task (task)" :start 1617735351000 :end 1617735351000}]
               (:tasks build))))))

  (testing "should report worker for task"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4
                                                             {:do [(a-task "abcd1234" "task one")
                                                                   (a-task "defg987" "another task")]})
                                                  (some-events 4
                                                               {:data {:time 1617735351 :origin {:id "abcd1234"}}}
                                                               {:event "selected-worker"
                                                                :data {:time 1617735352 :origin {:id "abcd1234"} :selected_worker "qwerty1234"}}
                                                               {:data {:time 1617735353 :origin {:id "abcd1234"}}}
                                                               {:event "selected-worker"
                                                                :data {:time 1617735354 :origin {:id "defg987"} :selected_worker "poiuy0987"}}
                                                               {:data {:time 1617735360 :origin {:id "defg987"}}}
                                                               {:data {:time 1617735361 :origin {:id "defg987"}}}))
      (let [[[_, [build]]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (= [{:name "task one (task)" :start 1617735351000 :end 1617735353000 :worker "qwerty1234"}
                {:name "another task (task)" :start 1617735354000 :end 1617735361000 :worker "poiuy0987"}]
               (:tasks build))))))

  (testing "should handle a plan with an on_failure step"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4
                                                             {:on_failure {:step (a-task "abcd1234" "task one")
                                                                           :on_failure (a-task "defg987" "another task")}})
                                                  (some-events 4
                                                               {:event "selected-worker"
                                                                :data {:time 1617735352 :origin {:id "abcd1234"} :selected_worker "qwerty1234"}}
                                                               {:event "selected-worker"
                                                                :data {:time 1617735354 :origin {:id "defg987"} :selected_worker "poiuy0987"}}))
      (let [[[_, [build]]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (= [{:name "task one (task)" :start 1617735352000 :end 1617735352000 :worker "qwerty1234"}
                {:name "another task (task)" :start 1617735354000 :end 1617735354000 :worker "poiuy0987"}]
               (:tasks build))))))

  (testing "should make no requests for builds if they are not accessed"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job")))
      ;; no expectation, implicitly tests by not failing from missing route
      (first (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"}))))

  (testing "should handle an aborted build if no plan existed yet"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "aborted"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (no-plan 4))
      (is (= (sut/concourse-builds {:base-url "http://concourse:8000"
                                    :bearer-token "fake-token"
                                    :team-name "my-team"})
             '(["my-pipeline my-job"
                [{:job-name "my-pipeline my-job"
                  :build-id "42"
                  :outcome "fail"
                  :start 1451642400000
                  :end 1451642401000}]])))))

  (testing "should find triggering build for job with triggering inputs"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"
                                                                   {:inputs [{:name :git :trigger true :passed ["previous-job"]}]})
                                                            (a-job "my-team" "my-pipeline" "previous-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "aborted"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4
                                                                  {:name "git"
                                                                   :version {:ref "abcd1234"}
                                                                   :first_occurrence true})
                                                  (some-plan 4)
                                                  (some-events 4)
                                                  (some-resource-versions "my-team" "my-pipeline" "git"
                                                                          {:id 1234 :version {:ref "abcd1234"}})
                                                  (some-resource-version-input-to "my-team" "my-pipeline" "git" 1234
                                                                                  {:id 4 :pipeline_name "my-pipeline" :job_name "my-job" :name "42"}
                                                                                  {:id 2 :pipeline_name "my-pipeline" :job_name "previous-job" :name "4277"}))
      (let [[[_, [build]]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (= [{:job-name "my-pipeline previous-job" :build-id "4277"}]
               (:triggered-by build))))))

  (testing "should find multiple triggering build from same input"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"
                                                                   {:inputs [{:name :git :trigger true :passed ["previous-job" "another-job"]}]})
                                                            (a-job "my-team" "my-pipeline" "previous-job")
                                                            (a-job "my-team" "my-pipeline" "another-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "aborted"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4
                                                                  {:name "git"
                                                                   :version {:ref "abcd1234"}
                                                                   :first_occurrence true})
                                                  (some-plan 4)
                                                  (some-events 4)
                                                  (some-resource-versions "my-team" "my-pipeline" "git"
                                                                          {:id 1234 :version {:ref "abcd1234"}})
                                                  (some-resource-version-input-to "my-team" "my-pipeline" "git" 1234
                                                                                  {:id 4 :pipeline_name "my-pipeline" :job_name "my-job" :name "42"}
                                                                                  {:id 2 :pipeline_name "my-pipeline" :job_name "previous-job" :name "4277"}
                                                                                  {:id 3 :pipeline_name "my-pipeline" :job_name "another-job" :name "007"}))
      (let [[[_, [build]]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (= [{:job-name "my-pipeline previous-job" :build-id "4277"}
                {:job-name "my-pipeline another-job" :build-id "007"}]
               (:triggered-by build))))))

  (testing "should not report build for job if input does not trigger automatically"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"
                                                                   {:inputs [{:name :git :trigger false :passed ["previous-job"]}]})
                                                            (a-job "my-team" "my-pipeline" "previous-job"))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "aborted"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4
                                                                  {:name "git"
                                                                   :version {:ref "abcd1234"}
                                                                   :first_occurrence true})
                                                  (some-plan 4)
                                                  (some-events 4)
                                                  (some-resource-versions "my-team" "my-pipeline" "git"
                                                                          {:id 1234 :version {:ref "abcd1234"}})
                                                  (some-resource-version-input-to "my-team" "my-pipeline" "git" 1234
                                                                                  {:id 4 :pipeline_name "my-pipeline" :job_name "my-job" :name "42"}
                                                                                  {:id 2 :pipeline_name "my-pipeline" :job_name "previous-job" :name "4277"}))
      (let [[[_, [build]]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (nil? (:triggered-by build))))))

  (testing "should handle an input missing from an older build (if configured in a newer version of the job)"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"
                                                                   {:inputs [{:name :git :trigger true :passed ["previous-job"]}]}))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 4
                                                                :name "42"
                                                                :status "aborted"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 4)
                                                  (some-plan 4)
                                                  (some-events 4)
                                                  (some-resource-versions "my-team" "my-pipeline" "git"
                                                                          {:id 1234 :version {:ref "abcd1234"}}))
      (let [[[_, [build]]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (nil? (:triggered-by build))))))

  (testing "should only report for a build that was triggered by the first version change (and not user run)"
    (fake/with-fake-routes-in-isolation (serve-up (valid-session)
                                                  (all-jobs (a-job "my-team" "my-pipeline" "my-job"
                                                                   {:inputs [{:name :git :trigger true :passed ["previous-job"]}]}))
                                                  (some-builds "my-team" "my-pipeline" "my-job"
                                                               {:id 5
                                                                :name "43"
                                                                :status "succeeded"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 55)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 40)}
                                                               {:id 4
                                                                :name "42"
                                                                :status "aborted"
                                                                :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-builds-up-to 4
                                                                     "my-team" "my-pipeline" "my-job"
                                                                     {:id 4
                                                                      :name "42"
                                                                      :status "aborted"
                                                                      :start_time (unix-time-in-s 2016 1 1 10 0 0)
                                                                      :end_time (unix-time-in-s 2016 1 1 10 0 1)})
                                                  (some-resources 5
                                                                  {:name "git"
                                                                   :version {:ref "abcd1234"}
                                                                   :first_occurrence false})
                                                  (some-resources 4
                                                                  {:name "git"
                                                                   :version {:ref "abcd1234"}
                                                                   :first_occurrence true})
                                                  (some-plan 5)
                                                  (some-events 5)
                                                  (some-plan 4)
                                                  (some-events 4)
                                                  (some-resource-versions "my-team" "my-pipeline" "git"
                                                                          {:id 1234 :version {:ref "abcd1234"}})
                                                  (some-resource-version-input-to "my-team" "my-pipeline" "git" 1234
                                                                                  {:id 4 :pipeline_name "my-pipeline" :job_name "my-job" :name "43"}
                                                                                  {:id 4 :pipeline_name "my-pipeline" :job_name "my-job" :name "42"}
                                                                                  {:id 2 :pipeline_name "my-pipeline" :job_name "previous-job" :name "4277"}))
      (let [[[_, builds]] (sut/concourse-builds {:base-url "http://concourse:8000"
                                                  :bearer-token "fake-token"
                                                  :team-name "my-team"})]
        (is (= [{:job-name "my-pipeline my-job"
                 :build-id "43"}
                {:job-name "my-pipeline my-job"
                 :build-id "42"
                 :triggered-by [{:job-name "my-pipeline previous-job" :build-id "4277"}]}]
               (map #(select-keys % [:job-name :build-id :triggered-by])
                    builds)))))))
