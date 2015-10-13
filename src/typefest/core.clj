(ns typefest.core
  (:require [lanterna.screen :as s]
            [com.rpl.specter :as spctr]
            [clj-sockets.core :as skt]
            [com.gearswithingears.async-sockets :as askt]
            [clojure.data :as cdata]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [clojure.core.async :as async])
  (:gen-class))
(let [log-chan (async/chan 1000)]
  (async/go-loop []
    (apply println (async/<! log-chan))
    (recur))
  (defn log [& args]
    (async/go
      (async/>! log-chan args))))

(defonce words
  (with-open [rdr (io/reader "resources/sgb-words.txt")]
    (vec (line-seq rdr))))

(defn clear [scr x y l]
  (s/put-string scr x y (apply str (repeat l " "))))

(defn refresh-board
  ([board scr]
   (s/clear scr)
   (loop [[[word {:keys [x y]}] & rst] board]
     (s/put-string scr x y)
     (recur rst))
   (s/redraw scr))
  ([old-board new-board scr]
   (loop [[[word {:keys [x y]}] & rst] old-board]
     (clear scr x y (count word))
     (recur rst))
   (loop [[[word {:keys [x y]}] & rst] new-board]
     (s/put-string scr x y)
     (recur rst))
   (s/redraw scr)))

(defn update-game-state [game-state new-entries]
  (reduce (fn [{:keys [board users] :as state} [user-name entry]]
            (if (contains? board entry)
              (-> state
                  (update :board dissoc entry)
                  (update-in [:users user-name entry] (fnil inc 0)))
              state))
          game-state new-entries))


(defn rand-word-obj [{:keys [board]}]
  (loop []
    (let [x (rand-nth words)]
      (if (contains? board x)
        (recur)
        [x (rand-int 100) 0]))))

(defn move-words [game-state & [new-word x y :as w-obj]]
  (let [{:keys [board] :as w} (update game-state :board
                                      (fn [brd]
                                        (reduce (fn [acc [w {:keys [x y] :as p}]]
                                                  (let [new-y (inc y)]
                                                    (if-not (< new-y 80) acc
                                                      (assoc acc w {:x x :y new-y})))) {} brd)))]
    (if-not (and w-obj (not (contains? board new-word))) w
      (assoc-in w [:board new-word] {:x x :y y}))))

(defn accept-user-input [user-name conn-chan]
  (async/go-loop []
    (when-let [ip (read-line)]
      (async/>! conn-chan [user-name ip])
      (recur))))


(let [cli-options [["-h" "--help"]
                   ["-p" "--port port" "server port" :default 54321 :parse-fn #(Integer/parseInt %)]
                   ["-u" "--user name" "user name for this connection"]]]
  (defn client [& args]
    (let [{{:keys [port help user]} :options} (cli/parse-opts args cli-options)]
      )))

(defn merge-chan-of-chans [chan-of-chans & {:keys [name]}]
  (let [out (async/chan 10)]
    (async/go-loop [all-chans #{chan-of-chans}]
      (if (empty? all-chans)
        (async/close! out)
        (let [[v c] (async/alts! (vec all-chans))]
          (if (nil? v)
            (recur (disj all-chans c))
            (if (= c chan-of-chans)
              (recur (conj all-chans v))
              (if (async/>! out v) (recur all-chans)
                  (doseq [c all-chans]
                    (async/close! c))))))))
    out))

(defn random-client [[user-name evts-ts] broadcast-chan conn-chans-chan]
  (let [[start-ts & evts-ts] evts-ts
        conn-chan (async/chan)]
    (async/go
      (async/<! (async/timeout start-ts))
      (when (async/>! conn-chans-chan conn-chan)
        (loop [[evt-ts & evts-ts] evts-ts]
          (let [gs (async/<! broadcast-chan)]
            (if-let [rand-correct-current-word (-> gs :board keys rand-nth)]
              (let [word-to-send (apply str rand-correct-current-word (if (> 8 (rand-int 10)) "-incorrect"))]
                (async/>! conn-chan word-to-send)))
            (async/<! (async/timeout evt-ts)))
          (if evts-ts (recur evts-ts) (async/close! conn-chan))) nil))))

(defn random-clients [broadcast-chan conn-chans-chan num-users]
  (let [users (map (fn [uid]
                     (let [user (format "user-%03d" (inc uid))
                           evt-ts (repeatedly (+ 10 (rand-int 20)) #(rand-int 20))]
                       [user evt-ts])) (range num-users))
        client-chans (mapv #(random-client % broadcast-chan conn-chans-chan) users)]
    (log :num-clients-spawned (count client-chans))
    (async/<!! (async/merge client-chans))))

(defn contiguous-seq-splitter [x group-fn per-grp-stateful-transducer-creator]
  (mapv (fn [[k vs]]
          [k (into [] (per-grp-stateful-transducer-creator k) vs)])
        (group-by group-fn x)))

(defn contiguous-chan-logger [ch group-fn per-grp-stateful-transducer-creator]
  (let [fch (async/chan)
        pub (async/pub fch group-fn)]
    (async/go
      (loop [st {}]
        (when-let [x (async/<! ch)]
          (let [grp (group-fn x)
                new-st (if (contains? st grp) st
                           (assoc st grp
                                  (async/sub pub grp
                                             (async/chan (async/sliding-buffer 0) (comp (per-grp-stateful-transducer-creator grp) (map log))))))]
            (async/>! fch x)
            (recur new-st))))
      (async/close! fch))))

(defn per-group-stateful-transducer-creator
  [iter-key-fn iterator-fn first-log-iter-id next-log-iter-fn]
  (fn trdr-creator [name]
    (let [exptd-nxt-iter-key (volatile! nil)
          cur-val (volatile! nil)
          next-log-iter (volatile! first-log-iter-id)]
      (fn trdr [rf]
        (fn rdcr
          ([] (rf))
          ([result]
           (when-let [[c-skey c-iter] @cur-val]
             (rf result [name c-skey c-iter]))
           (rf result))
          ([result input]
           (let [key (iter-key-fn input)]
             (if-let [nxt-iter-key @exptd-nxt-iter-key]
               (if (= nxt-iter-key key)
                 (let [[c-skey c-iter] (vswap! cur-val update 1 inc)
                       exptd-nxt-iter-key (vswap! exptd-nxt-iter-key iterator-fn)]
                   (if (>= c-iter @next-log-iter)
                     (do
                       (vswap! next-log-iter next-log-iter-fn)
                       (rf result [name c-skey c-iter]))
                     result))
                 (let [[p-skey p-iter] @cur-val
                       ret (rf result [name p-skey p-iter])]
                   (vreset! cur-val [key 1])
                   (vreset! exptd-nxt-iter-key (iterator-fn key))
                   (vreset! next-log-iter first-log-iter-id)
                   ret))
               (do
                 (vreset! exptd-nxt-iter-key (iterator-fn key))
                 (vreset! cur-val [key 1])
                 (vreset! next-log-iter first-log-iter-id)
                 result)))))))))

(defn server-chans []
  (let [broadcast-chan (async/chan)
        conn-chans-chan (async/chan)
        game-duration-in-minutes 5
        game-over-channel (async/timeout (* game-duration-in-minutes 60 1000))
        delay 5]
    (async/go
      (let [entries-chan (merge-chan-of-chans conn-chans-chan)]
        (loop [frame-rate-chan (async/timeout delay) gs {:board {}} i 0]
          (let [i (inc i)]
            (seq-log :iter i)
            (async/alt!
              entries-chan ([x]
                            (seq-log :entries i x)
                            (recur frame-rate-chan (update-game-state gs [x]) i))
              frame-rate-chan ([v c]
                               (let [[w x y :as w-obj] (rand-word-obj gs)
                                     new-gs (move-words gs w x y)]
                                 (seq-log :add-new-word i w-obj)
                                 (recur (async/timeout delay) new-gs i)))
              [[broadcast-chan gs]] ([v]
                                     (seq-log :broadcast i v)
                                     (recur frame-rate-chan gs i))
              game-over-channel ([_]
                                 (seq-log nil i)
                                 (log :game-over)
                                 (log :final-score (:users gs))))))
        (async/close! broadcast-chan)
        (async/close! entries-chan)))
    [broadcast-chan conn-chans-chan game-over-channel]))

(let [cli-options [["-h" "--help"]
                   ["-p" "--port port" "server port to listen on" :default 54321 :parse-fn #(Integer/parseInt %)]]]
  (defn server [& args]
    (let [{{:keys [port help]} :options} (cli/parse-opts args cli-options)
          [broadcast-chan conn-chans-chan game-over-channel] (server-chans)]
      (random-clients broadcast-chan conn-chans-chan 2)
      (async/go-loop [scr (s/get-screen) i 0]
        (log :screen-refresh-loop " " i)
        (when-let [{:keys [board]} (async/<! broadcast-chan)]
          (refresh-board board scr)
          (async/<! (async/timeout 30))
          (recur scr (inc i))))
      (async/<!! game-over-channel)
      (log "game over"))))

(let [cli-options [["-h" "--help"]]]
 (defn -main [& args]
   (let [{:keys [arguments] {:keys [help]} :options :as w} (cli/parse-opts args cli-options :in-order true)]
     (if help
       (log "available sub commands : server client")
       (let [[sub-command & args] arguments]
         (apply (case sub-command
                  "client" client
                  "server" server) args))))))
#_ (-main "server")
(+ 1 2)
