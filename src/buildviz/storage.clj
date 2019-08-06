(ns buildviz.storage
  (:require [buildviz.util.json :as json]
            [buildviz.safe-filenames :as safe-filenames]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- filename [& parts]
  (safe-filenames/encode (apply str parts)))

(defn store-build! [base-dir job-name build-id build-data]
  (let [job-dir (io/file base-dir (filename job-name))]
    (.mkdirs job-dir)
    (let [build-file (io/file job-dir (filename build-id ".json"))]
      (spit build-file (json/to-string build-data)))))


(defn load-builds [base-dir]
  (->> (io/file base-dir)
       .listFiles
       (filter #(.isDirectory %))
       (mapcat #(.listFiles %))
       (map slurp)
       (map json/from-string)))

(defn store-testresults! [base-dir job-name build-id test-xml]
  (let [job-dir (io/file base-dir (filename job-name))]
    (.mkdirs job-dir)
    (let [testresults-file (io/file job-dir (filename build-id ".xml"))]
      (spit testresults-file test-xml))))
