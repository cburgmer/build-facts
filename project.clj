(defproject build-facts "0.6.1"
  :description "Dump your build pipeline's data for inspection"
  :url "https://github.com/cburgmer/build-facts"
  :license {:name "BSD 2-Clause"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/tools.logging "1.2.2"]
                 [org.apache.logging.log4j/log4j-api "2.16.0"]
                 [org.apache.logging.log4j/log4j-core "2.16.0"]
                 [clj-http "3.12.3"]
                 [clj-time "0.15.2"]
                 [cheshire "5.10.1"]
                 [clj-commons/clj-yaml "0.7.107"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/tools.cli "1.0.206"]
                 [progrock "0.1.2"]
                 [uritemplate-clj "1.3.1"]
                 [wharf "0.2.0-20141115.032457-2"]]
  :main build-facts.main
  :aot [build-facts.main]
  :jar-exclusions [#"docker/.*"]
  :profiles {:dev {:dependencies [[clj-http-fake "1.0.3"]
                                  [com.github.tomakehurst/wiremock "2.27.2"]
                                  [luposlip/json-schema "0.3.3"]]
                   :plugins [[lein-ancient "1.0.0-RC3"]
                             [lein-nvd "1.9.0"]]}
             :test {:resource-paths ["test/resources" "test/integration/teamcity-recording.jar"]}}
  :jvm-opts ["-Dclojure.tools.logging.factory=clojure.tools.logging.impl/log4j2-factory"
             "--illegal-access=deny"]) ; https://clojure.org/guides/faq#illegal_access
