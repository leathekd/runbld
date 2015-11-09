(defproject co.elastic/runbld
  (try (-> "resources/version.txt" slurp .trim)
       (catch java.io.FileNotFoundException _
         (println "WARNING! Missing resources/version.txt,"
                  "falling back to 0.0.1-SNAPSHOT")
         "0.0.1-SNAPSHOT"))
  :description "They wrap"
  :url "https://github.com/elastic/runbld"
  :license {:name "Apache License, Version 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"}
  :global-vars {*warn-on-reflection* false}
  :min-lein-version "2.0.0"
  :exclusions [org.clojure/clojure]
  :dependencies [[clj-jgit "0.8.8"]
                 [clj-time "0.11.0"]
                 [circleci/clj-yaml "0.5.4"]
                 [com.draines/postal "1.11.3"]
                 [com.fzakaria/slf4j-timbre "0.2.1"]
                 [com.taoensso/timbre "4.1.4"]
                 [environ "1.0.1"]
                 [org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.cli "0.3.3"]
                 [elastic/elasticsearch-clojure "0.99.0"]
                 [slingshot "0.12.2"]
                 [stencil "0.5.0"]]
  :injections [(require 'clojure.pprint)]
  :profiles {:dev {:env {:dev true}}
             :package {:plugins [[lein-bin "0.3.4"]]
                       :bin {:bootclasspath true}}}
  :plugins [[lein-environ "1.0.1"]]
  :aliases {"package" ["with-profile" "package" "bin"]}
  :aot :all
  :main runbld.main
  :test-selectors {:default (complement :integration)
                   :integration :integration})
