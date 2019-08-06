(defproject buildviz "0.14.1"
  :description "Transparency for your build pipeline's results and runtime."
  :url "https://github.com/cburgmer/buildviz"
  :license {:name "BSD 2-Clause"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.logging "0.4.1"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jdmk/jmxtools
                                                    com.sun.jmx/jmxri]]
                 [clj-http "3.9.1"]
                 [clj-time "0.15.1"]
                 [cheshire "5.8.1"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/tools.cli "0.4.1"]
                 [intervox/clj-progress "0.2.1"]
                 [uritemplate-clj "1.2.1"]
                 [wharf "0.2.0-20141115.032457-2"]]
  :ring {:handler buildviz.main/app
         :init buildviz.main/help}
  :aot [buildviz.go.sync
        buildviz.jenkins.sync
        buildviz.teamcity.sync
        buildviz.data.junit-xml]
  :profiles {:dev {:dependencies [[clj-http-fake "1.0.3"]]}
             :test {:resource-paths ["test/resources"]}}
  :jvm-opts ["--illegal-access=deny"]) ; https://clojure.org/guides/faq#illegal_access
