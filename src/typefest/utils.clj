(ns typefest.utils
  (:require
   [clojure.core.async :as async]))
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
             :default (recur s))
       (do
         (async/>! words-chan s)
         (async/close! type-buffer-chan)
         (async/close! words-chan))))
   [type-buffer-chan words-chan]))
