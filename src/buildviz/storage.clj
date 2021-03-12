(ns buildviz.storage
  (:require [buildviz.util.json :as json]
            [buildviz.safe-filenames :as safe-filenames]
            [clojure.java.io :as io]))

(defn- filename [& parts]
  (safe-filenames/encode (apply str parts)))

(defn store-build! [base-dir job-name build-id build-data]
  (let [job-dir (io/file base-dir (filename job-name))]
    (.mkdirs job-dir)
    (let [build-file (io/file job-dir (filename build-id ".json"))]
      (spit build-file (json/to-string build-data)))))
