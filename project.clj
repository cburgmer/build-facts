(defproject build-facts "0.5.1"
  :description "Dump your build pipeline's data for inspection"
  :url "https://github.com/cburgmer/build-facts"
  :license {:name "BSD 2-Clause"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[org.clojure/clojure "1.10.2"]
                 [org.clojure/tools.logging "1.1.0"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jdmk/jmxtools
                                                    com.sun.jmx/jmxri]]
                 [clj-http "3.12.1"]
                 [clj-time "0.15.2"]
                 [cheshire "5.10.0"]
                 [clj-commons/clj-yaml "0.7.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/tools.cli "1.0.194"]
                 [progrock "0.1.2"]
                 [uritemplate-clj "1.3.0"]
                 [wharf "0.2.0-20141115.032457-2"]]
  :main build-facts.main
  :aot [build-facts.main]
  :profiles {:dev {:dependencies [[clj-http-fake "1.0.3"]]
                   :plugins [[lein-ancient "1.0.0-RC3"]]}
             :test {:resource-paths ["test/resources"]}}
  :jvm-opts ["--illegal-access=deny"]) ; https://clojure.org/guides/faq#illegal_access
