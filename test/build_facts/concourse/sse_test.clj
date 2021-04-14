(ns build-facts.concourse.sse-test
  (:require [build-facts.concourse.sse :as sut]
            [clojure.test :refer :all])
  (:import [java.io InputStream]))

(defn- mock-input-stream [stream]
  (let [remaining-stream (atom stream)]
    (proxy [InputStream] []
      (read [bytes] (if-let [value-read (first @remaining-stream)]
                      (do (->> value-read
                               (map-indexed (fn [i c] (aset-byte bytes i c)))
                               doall)
                          (swap! remaining-stream rest)
                          (count value-read))
                      (throw (Error. "Read past last event")))))))

(deftest test-concourse-sse
  (testing "should handle only end event"
    (sut/load-events (mock-input-stream ["event: end\n\n"])))

  (testing "should return simple event"
    (is (= '({:id "42"}
             {:event "end"})
           (sut/load-events (mock-input-stream ["id: 42\n\n" "event: end\n\n"])))))

  (testing "should return event with multiple keys"
    (is (= '({:id "42"
              :event "event"
              :data "{}"}
             {:event "end"})
           (sut/load-events (mock-input-stream ["id: 42\nevent: event\ndata: {}\n\n" "event: end\n\n"])))))

  (testing "should handle multiple events in one batch"
    (is (= '({:id "42"}
             {:id "43"}
             {:event "end"})
           (sut/load-events (mock-input-stream ["id: 42\n\nid: 43\n\n" "event: end\n\n"])))))

  (testing "should handle event split across batches"
    (is (= '({:id "42"}
             {:event "end"})
           (sut/load-events (mock-input-stream ["id: 42\n" "\n" "event: end\n\n"])))))

  (testing "should handle end event split across batches"
    (is (= '({:id "42"}
             {:event "end"})
           (sut/load-events (mock-input-stream ["id: 42\n\n" "event: " "end\n\n"])))))

  (testing "should handle another event following the end in the last batch"
    (is (= '({:event "end"}
             {:id "42"})
           (sut/load-events (mock-input-stream ["event: end\n\nid: 42\n\n"]))))))
