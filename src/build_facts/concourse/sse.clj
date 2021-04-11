(ns build-facts.concourse.sse
  (:require [clojure.string :as string]
            [clj-http.client :as client]
            [clj-http.conn-mgr :as conn])
  (:import [java.io InputStream]))

;; inspired by https://gist.github.com/oliyh/2b9b9107e7e7e12d4a60e79a19d056ee

(def event-mask (re-pattern (str "(?s).+?\n\n")))

(defn- parse-event [raw-event]
  (->> (re-seq #"(.*): (.*)\n?" raw-event)
       (map #(drop 1 %))
       (group-by first)
       (reduce (fn [acc [k v]]
                 (assoc acc (keyword k) (string/join (map second v)))) {})))

(defn- lazy-event-stream [event-stream data close]
  (let [byte-array (byte-array (max 1 (.available event-stream)))
        bytes-read (.read event-stream byte-array)]

    (if (neg? bytes-read)

      (do (close)
          [])

      (let [data (str data (slurp byte-array))
            es (->> (re-seq event-mask data) (map parse-event))]
        (if (some #(= "end" (:event %)) es)
          (do (close)
              es)
          (lazy-cat es (lazy-event-stream event-stream (string/replace data event-mask "") close)))))))

(defn events [url params]
  (let [cm (conn/make-regular-conn-manager {})
        r (client/get url (merge params {:as :stream :connection-manager cm}))
        event-stream ^InputStream (:body r)]
    ;; Work around that `(.close event-stream)` seems blocking, so close via shutdown
    ;; Looks like we are just side-stepping the closing of the CloseableHttpResponse:
    ;; https://github.com/dakrone/clj-http/blob/dd15359451645f677b3e294164cf70330b92241d/src/clj_http/core.clj#L456
    (lazy-event-stream event-stream nil #(.shutdown cm))))
