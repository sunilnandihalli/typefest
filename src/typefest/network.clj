(ns typefest.network
  (:require
   [typefest.log-utils :as lu]
   [zeromq.zmq :as zmq]
   [taoensso.nippy :as nippy]
   [zeromq.sendable :as zmq-sendable]
   [zeromq.receivable :as zmq-receivable]
   [zeromq.device :as zmq-device]
   [clojure.core.async :as async]
   [full.async :as fasync]))



(defonce ctx (zmq/zcontext))



(defn subscribe-to-broadcast-endpoint [broadcast-end-point output-chan]
  (async/go
   (with-open [skt (-> (zmq/socket ctx :sub)
                       (zmq/connect broadcast-end-point)
                       (zmq/subscribe ""))]
     (lu/log :subscriber-started)
     (loop []
       (let [x (zmq/receive skt)
             data (nippy/thaw x)]
         ;(lu/log :broadcast-subscriber data)
         (if (async/>! output-chan data)
           (recur)))))))

(defn publish-to-broadcast-endpoint [broadcast-end-point input-chan]
  (async/go
    (with-open [skt (-> (zmq/socket ctx :pub)
                        (zmq/bind broadcast-end-point))]
      (let [bytes-input-chan (async/map (fn [x]
                                          ;(lu/log :broadcast-publisher x)
                                          (nippy/freeze x))
                                        [input-chan] 10)]
       (lu/log :publisher-started)
       (loop []
         (when-let [x (async/<! bytes-input-chan)]
           (zmq/send skt x)
           (recur)))))))

(defn put-to-push-endpoint [push-endpoint input-chan]
  (async/go
    (with-open [skt (-> (zmq/socket ctx :push)
                        (zmq/connect push-endpoint))]
      (let [bytes-input-chan (async/map (fn [x]
                                          (lu/log :put-to-push x)
                                          (nippy/freeze x)) [input-chan] 10)]
        (lu/log :pusher-started)
        (loop []
          (when-let [x (async/<! bytes-input-chan)]
            (zmq/send skt x)
            (recur)))))))

(defn take-from-pull-endpoint [pull-endpoint output-chan]
  (async/go
    (with-open [skt (-> (zmq/socket ctx :pull)
                        (zmq/bind pull-endpoint))]
      (lu/log :puller-started)
      (loop []
        (lu/log :waiting-for-receive-str)
        (let [x (zmq/receive skt)
              data (nippy/thaw x)]
          (lu/log :take-from-pull data)
          (if (async/>! output-chan data)
            (recur)))))))
