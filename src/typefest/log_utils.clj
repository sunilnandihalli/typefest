(ns typefest.log-utils
  (:require
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [full.async :as fasync]))

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
