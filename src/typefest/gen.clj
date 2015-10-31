(ns typefest.gen
  (:require [typefest.log-utils :as lu]
           [clojure.core.async :as async]))

(defn random-client [[user-name evts-ts] broadcast-chan entries-chan]
  (lu/log :user-name user-name :evts-ts evts-ts)
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
    (lu/log :num-clients-spawned (count client-chans))
    (async/<!! (async/merge client-chans))))
