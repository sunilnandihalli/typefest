(ns typefest.game
  (:require [typefest.log-utils :as lu]
            [typefest.utils :as utls]
            [typefest.network :as net]
            [clojure.core.async :as async]
            [clojure.java.io :as io]))

(defonce words
  (with-open [rdr (io/reader "resources/sgb-words.txt")]
    (vec (line-seq rdr))))

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

(defn server-chans []
  (let [broadcast-chan (async/chan)
        entries-chan (async/chan)
        game-duration-in-minutes (/ 1 1)
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
                               (lu/log :final-score (:users gs)))
            :priority true)))
      (async/close! broadcast-chan)
      (async/close! entries-chan))
    [broadcast-chan entries-chan game-over-channel]))

(defn accept-clients [broadcast-end-point entries-end-point broadcast-chan entries-chan]
  (net/publish-to-broadcast-endpoint broadcast-end-point broadcast-chan)
  (net/take-from-pull-endpoint entries-end-point entries-chan))

(defn server-connect [broadcast-end-point entries-end-point]
  (let [broadcast-chan (async/chan) entries-chan (async/chan)]
    (net/subscribe-to-broadcast-endpoint broadcast-end-point broadcast-chan)
    (net/put-to-push-endpoint entries-end-point entries-chan)
    [(utls/signal broadcast-chan {:rows 30 :cols 100}) entries-chan]))
