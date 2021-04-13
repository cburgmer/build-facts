(ns build-facts.concourse.sse
  (:require [clojure.string :as string]
            [clj-http.client :as client]
            [clj-http.conn-mgr :as conn]
            [clojure.tools.logging :as log])
  (:import [java.io InputStream]))

;; inspired by https://gist.github.com/oliyh/2b9b9107e7e7e12d4a60e79a19d056ee

(def event-mask (re-pattern (str "(?s).+?\n\n")))

(defn- parse-event [raw-event]
  (->> (re-seq #"(.*): (.*)\n?" raw-event)
       (map #(drop 1 %))
       (group-by first)
       (reduce (fn [acc [k v]]
                 (assoc acc (keyword k) (string/join (map second v)))) {})))

(defn- load-events [event-stream]
  (loop [events []
         data nil]
    (let [byte-array (byte-array 4096)
          bytes-read (.read event-stream byte-array)]

      (if (neg? bytes-read)
        (throw (Exception. "Premature end of event stream"))

        (let [new-data (str data (slurp byte-array))
              es (->> (re-seq event-mask new-data) (map parse-event))]
          (if (some #(= "end" (:event %)) es)
            (concat events es)
            (recur (concat events es) (string/replace new-data event-mask ""))))))))

(defn events [base-url relative-url params]
  (log/info (format "Retrieving %s" relative-url))
  (let [cm (conn/make-regular-conn-manager {})
        response (client/get (str base-url relative-url)
                             (merge params {:as :stream :connection-manager cm}))
        event-stream ^InputStream (:body response)
        events (load-events event-stream)]
    (log/info (format "Retrieved %s: %s" relative-url (:status response)))
    ;; Work around that `(.close event-stream)` seems blocking, so close via shutdown
    ;; Looks like we are just side-stepping the closing of the CloseableHttpResponse:
    ;; https://github.com/dakrone/clj-http/blob/dd15359451645f677b3e294164cf70330b92241d/src/clj_http/core.clj#L456
    (.shutdown cm)
    events))
