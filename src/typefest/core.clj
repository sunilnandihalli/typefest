(ns typefest.core
  (:require
   [typefest.log-utils :as lu]
   [typefest.game :as tfg]
   [typefest.utils :as utls]
   [typefest.text-display :as tftd]
   [superstring.core :as ss]
   [clojure.string :as str]
   [com.rpl.specter :as spctr]
   [clojure.data :as cdata]
   [clojure.pprint :as pp]
   [clojure.java.io :as io]
   [clojure.tools.cli :as cli]
   [clojure.core.async :as async]
   [full.async :as fasync])
  (:gen-class))



(let [cli-options [["-h" "--help"]
                   ["--bep" "--broadcast-end-point broadcast_endpoint" "server broadcast endpoint" :default "tcp://localhost:54321" :parse-fn identity]
                   ["--eep" "--entries-end-point connection_endpoint" "server entries endpoint" :default "tcp://localhost:54322" :parse-fn identity]
                   ["-s" "--show-screen" "show the screen"]]]
  (defn server [& args]
    (let [{{:keys [broadcast-end-point entries-end-point help show-screen]} :options} (cli/parse-opts args cli-options)
          [broadcast-chan entries-chan game-over-channel] (tfg/server-chans)
          _ (lu/log :about-to-start-display-screen)
          broadcast-chan (if show-screen
                           (let [[s c] (utls/signal-and-chan broadcast-chan {:rows 30 :cols 100})]
                             (lu/log :signal-and-chan-obtained)
                             (tftd/display-screen s 30) c) broadcast-chan)]
      (lu/log :server-now-accepting-clients)
      (tfg/accept-clients broadcast-end-point entries-end-point
                      broadcast-chan entries-chan)
      (async/<!! game-over-channel)
      (async/close! entries-chan)
      (lu/log "game over"))))



(let [cli-options [["-h" "--help"]
                   ["--bep" "--broadcast-end-point broadcast_endpoint" "server broadcast endpoint" :default "tcp://localhost:54321" :parse-fn identity]
                   ["--eep" "--entries-end-point connection_endpoint" "server entries endpoint" :default "tcp://localhost:54322" :parse-fn identity]
                   ["-u" "--user user-name" "user name to use for the client" :default "super-man"]]]
  (defn client [& args]
    (let [{{:keys [broadcast-end-point entries-end-point help user show-screen user]} :options} (cli/parse-opts args cli-options)
          [broadcast-signal entries-chan] (tfg/server-connect broadcast-end-point entries-end-point)]
      (tftd/display-screen broadcast-signal 30 entries-chan user))))
(let [cli-options [["-h" "--help"]]]
  (defn single-user [& args]
    (lu/log "running in single user mode")
    (let [srvr (async/thread (server))
          clnt (async/thread (client))]
      (async/<!! (async/merge [srvr clnt])))))

(let [cli-options [["-h" "--help"]]]
 (defn -main [& args]
   (let [{:keys [arguments] {:keys [help]} :options :as w} (cli/parse-opts args cli-options :in-order true)]
     (if help
       (lu/log "available sub commands : server client")
       (let [[sub-command & args :as fargs] arguments]
         (apply (case sub-command
                  "client" client
                  "server" server
                  (partial single-user sub-command)) args))))))
