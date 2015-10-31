(ns typefest.text-display
  (:require
   [typefest.utils :as utls]
   [typefest.log-utils :as lu]
   [lanterna.screen :as s]
   [clojure.core.async :as async]))

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
   (lu/log :getting-display-screen)
   (let [scr (s/get-screen :swing {:cols 100 :rows 30})
         frame-delay-ms (/ 1000.0 fps)]
     (lu/log :starting-display-screen)
     (s/start scr)
     (lu/log :display-screen-started)
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
         [type-buffer-chan words-chan] (utls/typebuffer char-chan)
         [type-buffer-signal type-buffer-chan] (utls/signal-and-chan type-buffer-chan "")]
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
