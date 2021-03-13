(ns buildviz.data.junit-xml
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]))

;; Parsing is following schema documented in http://llg.cubic.org/docs/junit/

(defn- is-failure? [testcase-elem]
  (some #(= :failure (:tag %))
        (:content testcase-elem)))

(defn- is-error? [testcase-elem]
  (some #(= :error (:tag %))
        (:content testcase-elem)))

(defn- is-skipped? [testcase-elem]
  (some #(= :skipped (:tag %))
        (:content testcase-elem)))

(defn- assert-not-nil [value msg]
  (if (nil? value)
    (throw (IllegalArgumentException. msg)))
  value)

(defn- parse-name [elem]
  (:name (:attrs elem)))

(defn- ignore-human-readable-formatting [time]
  ;; work around https://github.com/junit-team/junit5/issues/1381
  (str/replace time "," ""))

(defn- parse-runtime [testcase-elem]
  (when-let [time (:time (:attrs testcase-elem))]
    (Math/round (* 1000 (Float/parseFloat (ignore-human-readable-formatting time))))))

(defn- parse-status [testcase-elem]
  (cond
    (is-failure? testcase-elem) "fail"
    (is-error? testcase-elem) "error"
    (is-skipped? testcase-elem) "skipped"
    :else "pass"))

(defn- parse-classname [testcase-elem]
  (if-let [classname (:classname (:attrs testcase-elem))]
    classname
    (:class (:attrs testcase-elem))))

(defn- add-runtime [testcase testcase-elem]
  (if-let [runtime (parse-runtime testcase-elem)]
    (assoc testcase :runtime runtime)
    testcase))

(defn- testcase [testcase-elem]
  (-> {:name (assert-not-nil (parse-name testcase-elem) "No name given for testcase")
       :status (parse-status testcase-elem)
       :classname (assert-not-nil (parse-classname testcase-elem) "No classname given for testcase")}
      (add-runtime testcase-elem)))

(defn- testsuite? [elem]
  (= :testsuite (:tag elem)))

(defn- testcase? [elem]
  (= :testcase (:tag elem)))

(defn- parseable-content [elem]
  (let [children (:content elem)]
    (filter #(or (testcase? %) (testsuite? %)) children)))

(declare parse-testsuite)

(defn- testsuite [testsuite-elem]
  {:name (assert-not-nil (parse-name testsuite-elem) "No name given for testsuite (or invalid element)")
   :children (->> (parseable-content testsuite-elem)
                  (map parse-testsuite)
                  doall ; realise XML parsing immediately, so we can catch errors without failing later once lazy sequence is evaluated
                  )})

(defn- parse-testsuite [elem]
  (if (testsuite? elem)
    (testsuite elem)
    (testcase elem)))

(defn- all-testsuites [root]
  (if (testsuite? root)
    (list root)
    (:content root)))

(defn parse-testsuites [junit-xml-result]
  (let [root (xml/parse-str junit-xml-result)]
    (map parse-testsuite
         (all-testsuites root))))
