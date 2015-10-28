(ns typefest.core
  (:require [zeromq.zmq :as zmq]
            [taoensso.nippy :as nippy]
            [zeromq.sendable :as zmq-sendable]
            [zeromq.receivable :as zmq-receivable]
            [zeromq.device :as zmq-device]
            [lanterna.screen :as s]
            [superstring.core :as ss]
            [clojure.string :as str]
            [com.rpl.specter :as spctr]
            [clj-sockets.core :as skt]
            [com.gearswithingears.async-sockets :as askt]
            [clojure.data :as cdata]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [clojure.core.async :as async]
            [full.async :as fasync])
  (:gen-class))
(let [log-chan (async/chan 1000)]
  (async/thread
    (with-open [f (io/writer "tmp.log")]
      (loop []
        (when-let [x (async/<!! log-chan)]
          (apply println x)
          (flush)
          (binding [*out* f]
            (apply println x))
          (recur)))))
  (defn log [& args]
    (async/>!! log-chan args))
  (defn finish-logging []
    (async/close! log-chan)))

(defonce words
  (with-open [rdr (io/reader "resources/sgb-words.txt")]
    (vec (line-seq rdr))))

(defn clear [scr x y l]
  (s/put-string scr x y (apply str (repeat l " "))))

(defn refresh-board
  ([{:keys [board rows cols]} scr & [type-buffer]]
   (s/clear scr)
   (loop [[[word {:keys [x y]}] & rst] (seq board)]
     (when word
       (s/put-string scr x y word)
       (recur rst)))
   (when type-buffer
     (s/put-string scr 0 (- rows 1) type-buffer))
   (s/redraw scr)))

(defn update-game-state [game-state new-entries]
  (reduce (fn [{:keys [board users] :as state} [user-name entry]]
            (if (contains? board entry)
              (-> state
                  (update :board dissoc entry)
                  (update-in [:users user-name entry] (fnil inc 0)))
              state))
          game-state new-entries))


(defn rand-word-obj [{:keys [board cols]}]
  (loop []
    (let [x (rand-nth words)]
      (if (contains? board x)
        (recur)
        [x (+ 2 (rand-int (- cols 10))) 0]))))

(defn move-words [{:keys [rows cols] :as game-state} & [new-word x y :as w-obj]]
  (let [{:keys [board rows cols] :as w}
        (update game-state :board
                (fn [brd]
                  (reduce (fn [acc [w {:keys [x y] :as p}]]
                            (let [new-y (inc y)]
                              (if-not (< new-y (- rows 3)) acc
                                      (assoc acc w {:x x :y new-y})))) {} brd)))]
    (if-not (and w-obj (not (contains? board new-word))) w
      (assoc-in w [:board new-word] {:x x :y y}))))

(defn accept-user-input [user-name conn-chan]
  (async/go-loop []
    (when-let [ip (read-line)]
      (async/>! conn-chan [user-name ip])
      (recur))))

(defn merge-chan-of-chans [chan-of-chans]
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

(defn random-client [[user-name evts-ts] broadcast-chan entries-chan]
  (log :user-name user-name :evts-ts evts-ts)
  (let [[start-ts & evts-ts] evts-ts]
    (async/go
      (async/<! (async/timeout (+ 2000 start-ts)))
      (loop [[evt-ts & evts-ts] evts-ts]
        (let [gs (async/<! broadcast-chan)]
          (if-let [rand-correct-current-word (-> gs :board keys rand-nth)]
            (let [word-to-send (apply str rand-correct-current-word (if (< 5 (rand-int 10)) "-incorrect"))]
              (async/>! entries-chan [user-name word-to-send])))
          (async/<! (async/timeout evt-ts)))
        (if evts-ts (recur evts-ts)))
      nil)))

(defn random-clients [broadcast-chan entries-chan num-users]
  (let [users (map (fn [uid]
                     (let [user (format "user-%03d" (inc uid))
                           evt-ts (repeatedly (+ 10 (rand-int 20)) #(rand-int 2000))]
                       [user evt-ts])) (range num-users))
        client-chans (mapv #(random-client % broadcast-chan entries-chan) users)]
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
      (let [st (loop [st {}]
                 (let [x (async/<! ch)]
                   (if x
                     (let [grp (group-fn x)
                           new-st (if (contains? st grp) st
                                      (assoc st grp (fasync/engulf
                                                     (async/sub pub grp
                                                                (async/chan 1
                                                                            (comp
                                                                             (per-grp-stateful-transducer-creator grp)
                                                                             (map log)))))))]
                       (async/>! fch x)
                      (recur new-st))
                     (do (async/close! fch) st))))]
        (async/<! (async/merge (map second st))))
      (async/close! fch))))

(defn per-group-stateful-transducer-creator
  [iter-key-fn iterator-fn first-log-iter-id next-log-iter-fn & [val-fn-i]]
  (fn trdr-creator [name]
    (fn trdr [rf]
      (let [val-fn (fn val-fn [skey iter input]
                     (into [name skey iter]
                           (if val-fn-i [(val-fn-i input)])))
            exptd-nxt-iter-key (volatile! nil)
            cur-val (volatile! nil)
            next-log-iter (volatile! first-log-iter-id)]
        (fn rdcr
          ([] (rf))
          ([result]
           (when-let [[c-skey c-iter] @cur-val]
             (rf result (val-fn c-skey c-iter nil))
             (vreset! cur-val nil)
             (vreset! exptd-nxt-iter-key nil)
             (vreset! next-log-iter first-log-iter-id))
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
                       (rf result (val-fn c-skey c-iter input)))
                     result))
                 (let [[p-skey p-iter] @cur-val
                       ret (rf result (val-fn p-skey p-iter input))]
                   (vreset! cur-val [key 1])
                   (vreset! exptd-nxt-iter-key (iterator-fn key))
                   (vreset! next-log-iter first-log-iter-id)
                   ret))
               (do
                 (vreset! exptd-nxt-iter-key (iterator-fn key))
                 (vreset! cur-val [key 1])
                 (vreset! next-log-iter first-log-iter-id)
                 result)))))))))

(defn start-seq-log []
  (let [ch (async/chan)
       finish-chan (contiguous-chan-logger
                    ch first
                    (per-group-stateful-transducer-creator
                     second inc 2 #(* 2 %) #(drop 2 %)))]
    {:seq-log (fn seq-log [& args]
                (async/>!! ch args))
     :finish-seq-log (fn finish-seq-log []
                       (async/close! ch)
                       (async/<!! finish-chan))}))

(defn server-chans []
  (let [broadcast-chan (async/chan)
        entries-chan (async/chan)
        game-duration-in-minutes (/ 1 4)
        game-over-channel (async/timeout (* game-duration-in-minutes 60 1000))
        delay 500]
    (async/go
      (loop [frame-rate-chan (async/timeout delay) gs {:board {} :rows 30 :cols 100} i 0]
        (let [i (inc i)]
          (async/alt!
            frame-rate-chan ([v c]
                             (let [[w x y :as w-obj] (rand-word-obj gs)
                                   new-gs (move-words gs w x y)]
                               (async/>! broadcast-chan new-gs)
                               (recur (async/timeout delay) new-gs i)))
            entries-chan ([x]
                          (let [new-gs (update-game-state gs [x])]
                            (async/>! broadcast-chan new-gs)
                            (recur frame-rate-chan  new-gs i)))
            game-over-channel ([_]
                               (log :final-score (:users gs)))
            :priority true)))
      (async/close! broadcast-chan)
      (async/close! entries-chan))
    [broadcast-chan entries-chan game-over-channel]))

(defn client-chans [& {:keys []}]
  (let [broadcast-chan (async/chan)
        conn-chan (async/chan)]))



(defn signal
  ([ch initial-value close?]
   (let [signal-chan (async/chan)]
     (async/go-loop [cur-val initial-value]
       (async/alt!
         ch ([v]
             (if-not (nil? v) (recur v)
                     (if close? (async/close! signal-chan)
                         (loop [nv cur-val]
                           (when (async/>! signal-chan nv)
                             (recur nv))))))
         [[signal-chan cur-val]] ([v c]
                                  (if v
                                    (recur cur-val)
                                    (async/close! ch)))))
     signal-chan))
  ([ch initial-value]
   (signal ch initial-value true)))

(defn clone-chan [ch n]
  (let [mult-ch (async/mult ch)]
    (vec (repeatedly n #(async/tap mult-ch (async/chan))))))

(defn signal-and-chan [ch signal-init-val]
  (let [[x y] (clone-chan ch 2)]
    [(signal x signal-init-val) y]))

(defn typebuffer [char-chan]
  (let [type-buffer-chan (async/chan)
        words-chan (async/chan 10)]
   (async/go-loop [s ""]
     (if-let [v (async/<! char-chan)]
       (cond
        (= v :enter) (do
                       (async/>! type-buffer-chan "")
                       (if (seq s) (async/>! words-chan s))
                       (recur ""))
        (= v :backspace) (let [new-s (apply str (drop-last s))]
                           (async/>! type-buffer-chan new-s)
                           (recur new-s))
        (and (char? v)
             (Character/isLetter v)) (let [new-s (str s (Character/toLowerCase v))]
                                       (async/>! type-buffer-chan new-s)
                                       (recur new-s))
             :default (do
                        (log :ignored v)
                        (recur s)))
       (do
         (async/>! words-chan s)
         (async/close! type-buffer-chan)
         (async/close! words-chan))))
   [type-buffer-chan words-chan]))

(defn key-press-chan-old  [scr repeat-rate]
  (let [ch (async/chan)
        delay (/ 1000 repeat-rate)]
    (async/go-loop [prev nil time-since-last-key-input 0]
      (async/<! (async/timeout 50))
      (let [d (+ time-since-last-key-input 50)]
       (if-let [x (s/get-key scr)]
         (if (= x prev)
           (if (> d delay)
             (if (async/>! ch x) (recur x 0))
             (recur prev d))
           (if (async/>! ch x) (recur x 0)))
         (recur prev 0))))
    ch))
(defn key-press-chan  [scr repeat-rate]
  (let [ch (async/chan)]
    (async/go-loop []
      (async/<! (async/timeout 50))
      (if-let [x (s/get-key scr)]
        (when (async/>! ch x)
          (recur))
        (recur)))
    ch))

(defn display-screen
  ([broadcast-signal fps]
   (log :getting-display-screen)
   (let [scr (s/get-screen :swing {:cols 100 :rows 30})
         frame-delay-ms (/ 1000.0 fps)]
     (log :starting-display-screen)
     (s/start scr)
     (log :display-screen-started)
     (async/go
       (loop []
         (when-let [gs (async/<! broadcast-signal)]
           (refresh-board gs scr)
           (async/<! (async/timeout frame-delay-ms))
           (recur)))
       (s/stop scr))))
  ([broadcast-signal fps entries-chan user]
   (let [scr (s/get-screen :swing {:cols 100 :rows 30})
         char-chan (key-press-chan scr 4)
         frame-delay-ms (/ 1000.0 fps)
         [type-buffer-chan words-chan] (typebuffer char-chan)
         [type-buffer-signal type-buffer-chan] (signal-and-chan type-buffer-chan "")]
     (async/go-loop []
       (when-let [x (async/<! words-chan)]
         (async/>! entries-chan [user x])
         (recur)))
     (s/start scr)
     (async/go
       (loop [delay-chan (async/timeout frame-delay-ms)]
         (async/alt!
           [delay-chan type-buffer-chan] ([_]
                                          (refresh-board (async/<! broadcast-signal) scr
                                                         (async/<! type-buffer-signal))
                                          (recur (async/timeout frame-delay-ms)))))))))

(defonce ctx (zmq/zcontext))



(defn subscribe-to-broadcast-endpoint [broadcast-end-point output-chan]
  (async/go
   (with-open [skt (-> (zmq/socket ctx :sub)
                       (zmq/connect broadcast-end-point)
                       (zmq/subscribe ""))]
     (log :subscriber-started)
     (loop []
       (let [x (zmq/receive skt)
             data (nippy/thaw x)]
         ;(log :broadcast-subscriber data)
         (if (async/>! output-chan data)
           (recur)))))))

(defn publish-to-broadcast-endpoint [broadcast-end-point input-chan]
  (async/go
    (with-open [skt (-> (zmq/socket ctx :pub)
                        (zmq/bind broadcast-end-point))]
      (let [bytes-input-chan (async/map (fn [x]
                                          ;(log :broadcast-publisher x)
                                          (nippy/freeze x))
                                        [input-chan] 10)]
       (log :publisher-started)
       (loop []
         (when-let [x (async/<! bytes-input-chan)]
           (zmq/send skt x)
           (recur)))))))

(defn put-to-push-endpoint [push-endpoint input-chan]
  (async/go
    (with-open [skt (-> (zmq/socket ctx :push)
                        (zmq/connect push-endpoint))]
      (let [bytes-input-chan (async/map (fn [x]
                                          (log :put-to-push x)
                                          (nippy/freeze x)) [input-chan] 10)]
        (log :pusher-started)
        (loop []
          (when-let [x (async/<! bytes-input-chan)]
            (zmq/send skt x)
            (recur)))))))

(defn take-from-pull-endpoint [pull-endpoint output-chan]
  (async/go
    (with-open [skt (-> (zmq/socket ctx :pull)
                        (zmq/bind pull-endpoint))]
      (log :puller-started)
      (loop []
        (log :waiting-for-receive-str)
        (let [x (zmq/receive skt)
              data (nippy/thaw x)]
          (log :take-from-pull data)
          (if (async/>! output-chan data)
            (recur)))))))

(defn accept-clients [broadcast-end-point entries-end-point broadcast-chan entries-chan]
  (publish-to-broadcast-endpoint broadcast-end-point broadcast-chan)
  (take-from-pull-endpoint entries-end-point entries-chan))

(defn server-connect [broadcast-end-point entries-end-point]
  (let [broadcast-chan (async/chan) entries-chan (async/chan)]
    (subscribe-to-broadcast-endpoint broadcast-end-point broadcast-chan)
    (put-to-push-endpoint entries-end-point entries-chan)
    [(signal broadcast-chan {:rows 30 :cols 100}) entries-chan]))

(let [cli-options [["-h" "--help"]
                   ["--bep" "--broadcast-end-point broadcast_endpoint" "server broadcast endpoint" :default "tcp://localhost:54321" :parse-fn identity]
                   ["--eep" "--entries-end-point connection_endpoint" "server entries endpoint" :default "tcp://localhost:54322" :parse-fn identity]
                   ["-s" "--show-screen" "show the screen"]]]
  (defn server [& args]
    (let [{{:keys [broadcast-end-point entries-end-point help show-screen]} :options} (cli/parse-opts args cli-options)
          [broadcast-chan entries-chan game-over-channel] (server-chans)
          _ (log :about-to-start-display-screen)
          broadcast-chan (if show-screen
                           (let [[s c] (signal-and-chan broadcast-chan {:rows 30 :cols 100})]
                             (log :signal-and-chan-obtained)
                             (display-screen s 30) c) broadcast-chan)]
      (log :server-now-accepting-clients)
      (accept-clients broadcast-end-point entries-end-point
                      broadcast-chan entries-chan)
      (async/<!! game-over-channel)
      (async/close! entries-chan)
      (log "game over"))))


#_ (-main "server" "-s")
(let [cli-options [["-h" "--help"]
                   ["--bep" "--broadcast-end-point broadcast_endpoint" "server broadcast endpoint" :default "tcp://localhost:54321" :parse-fn identity]
                   ["--eep" "--entries-end-point connection_endpoint" "server entries endpoint" :default "tcp://localhost:54322" :parse-fn identity]
                   ["-u" "--user user-name" "user name to use for the client" :default "super-man"]]]
  (defn client [& args]
    (let [{{:keys [broadcast-end-point entries-end-point help user show-screen user]} :options} (cli/parse-opts args cli-options)
          [broadcast-signal entries-chan] (server-connect broadcast-end-point entries-end-point)]
      (display-screen broadcast-signal 30 entries-chan user))))
(let [cli-options [["-h" "--help"]]]
  (defn single-user [& args]
    (log "running in single user mode")
    (let [srvr (async/thread (server))
          clnt (async/thread (client))]
      (async/<!! (async/merge [srvr clnt])))))

#_ (-main "client")
(let [cli-options [["-h" "--help"]]]
 (defn -main [& args]
   (let [{:keys [arguments] {:keys [help]} :options :as w} (cli/parse-opts args cli-options :in-order true)]
     (if help
       (log "available sub commands : server client")
       (let [[sub-command & args :as fargs] arguments]
         (apply (case sub-command
                  "client" client
                  "server" server
                  (partial single-user sub-command)) args))))))
