(ns build-facts.concourse.transform-test
  (:require [build-facts.concourse.transform :as sut]
            [cheshire.core :as j]
            [clojure.test :refer :all]))

(defn- an-event [event-data]
  {:data (j/generate-string event-data)})

(defn- a-build-with [{:keys [build resources plan events triggered-by]}]
  (cond-> {:build {:status "succeeded"}
           :resources (delay {:inputs []})
           :plan (delay)
           :events (delay)
           :triggered-by (delay)}
    build (assoc :build build)
    resources (assoc :resources (delay resources))
    plan (assoc :plan (delay plan))
    events (assoc :events (delay events))
    triggered-by (assoc :triggered-by (delay triggered-by))))


(deftest test-concourse-transform
  (testing "handles inputs with multiple keys"
    (is (= [{:source-id "something[another-key,key1]"
             :revision "more val,some val"}]
           (:inputs (sut/concourse->build (a-build-with {:build {:status "succeeded"}
                                                         :resources {:inputs [{:name "something"
                                                                               :version {:key1 "some val"
                                                                                         :another-key "more val"}}]}}))))))
  (testing "should not conflate different version with similar values"
    (let [inputs (:inputs (sut/concourse->build (a-build-with {:build {:status "succeeded"}
                                                               :resources {:inputs [{:name "first"
                                                                                     :version {:a ","
                                                                                               :b ""}}
                                                                                    {:name "second"
                                                                                     :version {:a ""
                                                                                               :b ","}}]}})))]
      (is (not= (:revision (first inputs))
                (:revision (second inputs))))))
  (testing "should escape soundly"
    (is (= "%252C,%2C"
           (-> (sut/concourse->build (a-build-with {:build {:status "succeeded"}
                                                    :resources {:inputs [{:name "first"
                                                                          :version {:a "%2C"
                                                                                    :b ","}}]}}))
               :inputs
               first
               :revision))))
  (testing "should handle a single event"
    (is (= [{:name "git (get)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:id "609a8bdf"
                                                                 :get {:name "git"}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})]}))
               :tasks))))
  (testing "should handle multiple events for a task"
    (is (= [{:name "git (get)" :start 1234567890000 :end 1300000000000 :worker "abcd1234"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:id "609a8bdf"
                                                                 :get {:name "git"}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})
                                                             (an-event {:data {:origin {:id "609a8bdf"}
                                                                               :time 1300000000}})]}))
               :tasks))))
  (testing "should handle a plan with a put"
    (is (= [{:name "git (put)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:id "609a8bdf"
                                                                 :put {:name "git"}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})]}))
               :tasks))))
  (testing "should handle a plan with a task"
    (is (= [{:name "my-task (task)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:id "609a8bdf"
                                                                 :task {:name "my-task"}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})]}))
               :tasks))))
  (testing "should handle a plan with parallel steps"
    (is (= [{:name "git (get)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:in_parallel {:steps [{:id "609a8bdf"
                                                                                        :get {:name "git"}}]}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})]}))
               :tasks))))
  (testing "should handle a plan with a on_failure configuration"
    (is (= [{:name "git (get)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:on_failure {:step {:id "609a8bdf"
                                                                                     :get {:name "git"}}}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})]}))
               :tasks))))
  (testing "should handle a plan with nested on_failure configuration"
    (is (= [{:name "git (get)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:on_failure {:on_failure {:id "609a8bdf"
                                                                                           :get {:name "git"}}}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})]}))
               :tasks))))
  (testing "should handle a plan with a on_success configuration"
    (is (= [{:name "git (get)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:on_success {:step {:id "609a8bdf"
                                                                                     :get {:name "git"}}}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})]}))
               :tasks))))
  (testing "should handle a plan with nested on_success configuration"
    (is (= [{:name "git (get)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:on_success {:on_success {:id "609a8bdf"
                                                                                           :get {:name "git"}}}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})]}))
               :tasks))))
  (testing "should handle a plan with a on_success configuration and multiple sub-steps"
    (is (= [{:name "git (get)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}
            {:name "version (get)" :start 1334567890000 :end 1334567890000 :worker "defg5678"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:on_success {:do [{:id "609a8bdf"
                                                                                    :get {:name "git"}}
                                                                                   {:id "901adeef"
                                                                                    :get {:name "version"}}]}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})
                                                             (an-event {:event "selected-worker"
                                                                        :data {:origin {:id "901adeef"}
                                                                               :time 1334567890
                                                                               :selected_worker "defg5678"}})]}))
               :tasks))))
  (testing "should handle a plan with a on_failure configuration and multiple sub-steps"
    (is (= [{:name "git (get)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}
            {:name "version (get)" :start 1334567890000 :end 1334567890000 :worker "defg5678"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:on_failure {:do [{:id "609a8bdf"
                                                                                    :get {:name "git"}}
                                                                                   {:id "901adeef"
                                                                                    :get {:name "version"}}]}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})
                                                             (an-event {:event "selected-worker"
                                                                        :data {:origin {:id "901adeef"}
                                                                               :time 1334567890
                                                                               :selected_worker "defg5678"}})]}))
               :tasks))))
  (testing "should handle a task with retry configuration"
    (is (= [{:name "test #1 (task)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}
            {:name "test #2 (task)" :start 1334567890000 :end 1334567890000 :worker "defg5678"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:retry [{:id "609a8bdf"
                                                                          :task {:name "test"}}
                                                                         {:id "901adeef"
                                                                          :task {:name "test"}}]}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})
                                                             (an-event {:event "selected-worker"
                                                                        :data {:origin {:id "901adeef"}
                                                                               :time 1334567890
                                                                               :selected_worker "defg5678"}})]}))
               :tasks))))
  (testing "should number multiple calls of the same resource if same type"
    (is (= [{:name "git #1 (get)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}
            {:name "git #2 (get)" :start 1334567890000 :end 1334567890000 :worker "defg5678"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:id "609a8bdf"
                                                                 :get {:name "git"}}
                                                                {:id "901adeef"
                                                                 :get {:name "git"}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})
                                                             (an-event {:event "selected-worker"
                                                                        :data {:origin {:id "901adeef"}
                                                                               :time 1334567890
                                                                               :selected_worker "defg5678"}})]}))
               :tasks))))
  (testing "should sort tasks by start time"
    (is (= [{:name "git #1 (get)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}
            {:name "git (put)" :start 1334567890000 :end 1334567890000 :worker "defg5678"}
            {:name "git #2 (get)" :start 1344567890000 :end 1344567890000 :worker "dfgh6789"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:id "609a8bdf"
                                                                 :get {:name "git"}}
                                                                {:on_success {:step {:id "901adeef"
                                                                                     :put {:name "git"}}
                                                                              :on_success {:id "0def898"
                                                                                           :get {:name "git"}}}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})
                                                             (an-event {:event "selected-worker"
                                                                        :data {:origin {:id "901adeef"}
                                                                               :time 1334567890
                                                                               :selected_worker "defg5678"}})
                                                             (an-event {:event "selected-worker"
                                                                        :data {:origin {:id "0def898"}
                                                                               :time 1344567890
                                                                               :selected_worker "dfgh6789"}})]}))
               :tasks))))
  (testing "should not number multiple calls of the same resource if type is different"
    (is (= [{:name "git (put)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}
            {:name "git (get)" :start 1334567890000 :end 1334567890000 :worker "defg5678"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:retry [{:id "609a8bdf"
                                                                          :put {:name "git"}}
                                                                         {:id "901adeef"
                                                                          :get {:name "git"}}]}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})
                                                             (an-event {:event "selected-worker"
                                                                        :data {:origin {:id "901adeef"}
                                                                               :time 1334567890
                                                                               :selected_worker "defg5678"}})]}))
               :tasks))))
  (testing "should handle a set_pipeline step"
    (is (= [{:name "my-pipeline (set_pipeline)" :start 1234567890000 :end 1234567890000 :worker "abcd1234"}]
           (-> (sut/concourse->build (a-build-with {:plan {:do [{:id "609a8bdf"
                                                                 :set_pipeline {:name "my-pipeline"}}]}
                                                    :events [(an-event {:event "selected-worker"
                                                                        :data {:origin {:id "609a8bdf"}
                                                                               :time 1234567890
                                                                               :selected_worker "abcd1234"}})]}))
               :tasks))))
  (testing "should handle triggered-by"
    (is (= [{:job-name "some-pipeline triggering-job" :build-id "1234"}]
           (:triggered-by (sut/concourse->build (a-build-with {:triggered-by [{:pipeline_name "some-pipeline" :job_name "triggering-job" :name "1234"}]})))))))
