(defproject typefest "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.371"]
                 [org.zeromq/jeromq "0.3.5"]
                 [org.zeromq/cljzmq "0.1.4" :exclusions [org.zeromq/jzmq]]
                 [superstring "2.1.0"]
                 [com.taoensso/nippy "2.10.0"]
                 ;[org.zeromq/cljzmq "0.1.4"]
                 ;[org.zeromq/jzmq "3.1.0"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/test.check "0.8.2"]
                 [fullcontact/full.async "0.8.15"]
                 [com.rpl/specter "0.8.0"]
                 ;[clj-sockets "0.1.0"]
                 ;[org.clojars.sunilnandihalli/async-sockets "0.1.2"]
                 [clojure-lanterna "0.9.4"]
                 [com.badlogicgames.gdx/gdx "1.7.1"]
                 [com.badlogicgames.gdx/gdx-backend-lwjgl "1.7.1"]
                 [com.badlogicgames.gdx/gdx-box2d "1.7.1"]
                 [com.badlogicgames.gdx/gdx-box2d-platform "1.7.1"
                  :classifier "natives-desktop"]
                 [com.badlogicgames.gdx/gdx-bullet "1.7.1"]
                 [com.badlogicgames.gdx/gdx-bullet-platform "1.7.1"
                  :classifier "natives-desktop"]
                 [com.badlogicgames.gdx/gdx-platform "1.7.1"
                  :classifier "natives-desktop"]
                 [play-clj "0.4.7"]]
  :source-paths ["src" "src-common"]
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :jvm-opts ["-Djava.library.path=/usr/lib:/usr/local/lib"]
  :main ^:skip-aot typefest.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
