(ns buildviz.concourse.sync-jobs
  (:require [buildviz.concourse
             [api :as api]]
            [buildviz.storage :as storage]
            [buildviz.util
             [json :as json]
             [url :as url]]
            [cheshire.core :as j]
            [clj-http.client :as client]
            [clj-progress.core :as progress]
            [clj-time
             [coerce :as tc]
             [core :as t]
             [format :as tf]]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [uritemplate-clj.core :as templ]))

(defn sync-jobs [concourse-target]
  (println "Concourse" concourse-target)
  (api/test-login concourse-target))
