(defproject typefest "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.zeromq/cljzmq "0.1.4"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/test.check "0.8.2"]
                 [com.rpl/specter "0.7.1"]
                 [clj-sockets "0.1.0"]
                 [org.clojars.sunilnandihalli/async-sockets "0.1.2"]
                 [clojure-lanterna "0.9.4"]]
  :main ^:skip-aot typefest.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
