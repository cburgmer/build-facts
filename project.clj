(defproject buildviz "0.14.1"
  :description "Transparency for your build pipeline's results and runtime."
  :url "https://github.com/cburgmer/buildviz"
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
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/tools.cli "1.0.194"]
                 [intervox/clj-progress "0.2.1"]
                 [uritemplate-clj "1.3.0"]
                 [wharf "0.2.0-20141115.032457-2"]]
  :aot [buildviz.go.sync
        buildviz.jenkins.sync
        buildviz.teamcity.sync
        buildviz.data.junit-xml]
  :profiles {:dev {:dependencies [[clj-http-fake "1.0.3"]]
                   :plugins [[lein-ancient "1.0.0-RC3"]]}
             :test {:resource-paths ["test/resources"]}}
  :jvm-opts ["--illegal-access=deny"]) ; https://clojure.org/guides/faq#illegal_access
