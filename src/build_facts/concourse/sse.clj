(ns build-facts.concourse.sse
  (:require [clojure.string :as string]
            [cheshire.core :as j])
  (:import [java.io InputStream]))

;; inspired by https://gist.github.com/oliyh/2b9b9107e7e7e12d4a60e79a19d056ee

(def event-mask (re-pattern (str "(?s).+?\n\n")))

(defn- parse-event [raw-event]
  (->> (re-seq #"(.*): (.*)\n?" raw-event)
       (map #(drop 1 %))
       (group-by first)
       (reduce (fn [acc [k v]]
                 (assoc acc (keyword k) (string/join (map second v)))) {})))

(defn load-events [event-stream]
  (loop [events []
         data nil]
    (let [byte-array (byte-array 4096)
          bytes-read (.read event-stream byte-array)]

      (if (neg? bytes-read)
        (throw (Exception. "Premature end of event stream"))

        (let [new-data (str data (String. byte-array 0 bytes-read))
              es (->> (re-seq event-mask new-data) (map parse-event))]
          (if (some #(= "end" (:event %)) es)
            (concat events es)
            (recur (concat events es) (string/replace new-data event-mask ""))))))))
