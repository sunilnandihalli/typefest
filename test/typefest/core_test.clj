(ns typefest.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [typefest.core :refer :all]))

(deftest moving-words-test
  (testing "move words"
    (is (= {:board {"hello" {:x 10 :y 15}} :rows 30 :cols 100}
           (move-words {:board {} :rows 30 :cols 100} "hello" 10 15)))
    (is (= {:board {} :rows 30 :cols 100}
           (move-words {:board {"hello" {:x 10 :y 26}} :rows 30 :cols 100})))
    (is (= {:board {"hello" {:x 10 :y 11}} :rows 30 :cols 100}
           (move-words {:board {"hello" {:x 10 :y 10}} :rows 30 :cols 100} "hello" 10 50)))
    (is (= {:board {"hello" {:x 10 :y 11}} :rows 30 :cols 100}
           (move-words {:board {"hello" {:x 10 :y 10}} :rows 30 :cols 100})))
    (is (= {:board {"hello" {:x 10 :y 11} "howdy" {:x 20 :y 0}} :rows 30 :cols 100}
           (move-words {:board {"hello" {:x 10 :y 10}} :rows 30 :cols 100} "howdy" 20 0)))))

(deftest update-game-state-test
  (testing "update-game-state"
    (is (= {:board {} :users {"user-001" {"hello" 1}}}
           (update-game-state {:board {"hello" {:x 10 :y 20}}} [["user-001" "hello"]])))
    (is (= {:board {"hello" {:x 10 :y 20}}}
           (update-game-state {:board {"hello" {:x 10 :y 20}}} [["user-001" "hello-incorrect"]])))
    (is (= {:board {"hello" {:x 10 :y 20}}}
           (update-game-state {:board {"hello" {:x 10 :y 20}}} [])))
    (is (= {:board {"hello" {:x 10 :y 20} "howdy" {:x 10 :y 50}}}
           (update-game-state {:board {"hello" {:x 10 :y 20} "howdy" {:x 10 :y 50}}} [])))
    (is (= {:board {} :users {"user-001" {"hello" 1}
                              "user-002" {"howdy" 1}}}
           (update-game-state {:board {"hello" {:x 10 :y 20} "howdy" {:x 10 :y 50}}}
                              [["user-001" "hello"] ["user-002" "howdy"]])))
    (is (= {:board {} :users {"user-001" {"hello" 2}
                              "user-002" {"howdy" 2}}}
           (update-game-state {:board {"hello" {:x 10 :y 20} "howdy" {:x 10 :y 50}}
                               :users {"user-001" {"hello" 1} "user-002" {"howdy" 1}}}
                              [["user-001" "hello"] ["user-002" "howdy"]])))))

(defn contiguous-seq-splitter-data-gen [split-data f]
  (sort-by second
           (mapcat (fn [[name init cnt]]
                     (map vector (repeat name) (take cnt (iterate f init)))) split-data)))

(defn contiguous-seq-splitter-data-canonical-rep [data]
  (sort-by first
           (map (fn [[k vs]] [k (frequencies (map first vs))])
                (group-by second data))))

(deftest contiguous-seq-splitter-data-canonical-rep-test
  (testing "contiguous-seq-splitter-data-canonical-rep-test"
    (is (= (contiguous-seq-splitter-data-canonical-rep
            [[:a 0] [:b 0] [:a 2] [:b 2] [:c 2]
             [:a 3] [:a 4] [:a 5] [:b 5] [:b 6]])
           [[0 {:a 1 :b 1}] [2 {:a 1 :b 1 :c 1}]
            [3 {:a 1}] [4 {:a 1}] [5 {:a 1 :b 1}] [6 {:b 1}]]))))

(defn split-data-canonical-rep [sd]
  (sort-by first
           (map (fn [[k vs]] [k (set vs)])
                (group-by (comp vec rest) sd))))

(deftest split-data-canonical-rep-test
  (testing "split-data-canonical-rep-test"
    (is (= [[[0 1] #{[:a 0 1] [:b 0 1]}]
            [[2 1] #{[:b 2 1] [:c 2 1]}]
            [[2 4] #{[:a 2 4]}]
            [[5 2] #{[:b 5 2]}]]
           (split-data-canonical-rep
            [[:a 0 1] [:b 0 1] [:b 2 1] [:c 2 1] [:a 2 4] [:b 5 2]])))))

(deftest contiguous-seq-splitter-data-gen-test
  (testing "contiguous-seq-splitter-data-gen-test"
    (is (=
         (contiguous-seq-splitter-data-canonical-rep
          (contiguous-seq-splitter-data-gen [[:a 0 1] [:b 0 1] [:b 2 1] [:c 2 1]
                                             [:a 2 4] [:b 5 2]] inc))
         [[0 {:a 1 :b 1}] [2 {:a 1 :b 1 :c 1}]
          [3 {:a 1}] [4 {:a 1}] [5 {:a 1 :b 1}] [6 {:b 1}]]))))

(defn print-and-ret [name]
  (fn [x]
    (println name " " x)
    (flush) x))

(defn tlog [ch name]
  (async/map (print-and-ret name) [ch]))


(deftest typebuffer-test
  (testing "typebuffer-test"
    (let [x (replace {\newline :enter} (seq "abcd\nefgh\ndfse"))
          res [["a" "ab" "abc" "abcd" ""
                "e" "ef" "efg" "efgh" ""
                "d" "df" "dfs" "dfse"]
               ["abcd" "efgh" "dfse"]]
          char-chan (async/chan)
          [buffer-chan words-chan] (typebuffer char-chan)]
      (async/onto-chan char-chan x)
      (is (= res
             [(async/<!! (async/into [] buffer-chan))
              (async/<!! (async/into [] words-chan))])))))

(deftest signal-test
  (testing "signal"
    (let [x (async/chan)
          y (signal x 10)]
      (is (= 10 (async/<!! y)))
      (async/>!! x 20)
      (is (= 20 (async/<!! y)))
      (async/>!! x 30)
      (async/>!! x 40)
      (is (= 40 (async/<!! y))))))

(deftest signal-and-chan-test
  (testing "signal-and-chan-test"
    (let [x (async/chan)
          [x y] (signal-and-chan x 10)])))

#_(deftest publish-subscribe-test
    (testing "publish-subscribe-test"
      (let [pub-input (async/chan)
            sub-output (async/chan)
            pub-data (mapv #(format "pub-msg-%02d" %) (range 10))
            endpoint "inproc://pub-sub-test"]
        (subscribe-to-broadcast-endpoint endpoint sub-output)
        (publish-to-broadcast-endpoint endpoint (tlog pub-input :pub-input))
        (async/onto-chan pub-input pub-data)
        (is (= pub-data (async/<!! (async/into [] (tlog sub-output :sub-output))))))))

#_(deftest push-pull-test
    (testing "push-pull-test"
      (let [push-input (async/chan)
            pull-output (async/chan)
            push-data (mapv #(format "push-msg-%02d" %) (range 10))
            endpoint "inproc://push-pull-test"]
        (put-to-push-endpoint endpoint (tlog push-input :push-input))
        (take-from-pull-endpoint endpoint pull-output)
        (async/onto-chan push-input push-data)
        (is (= push-data (async/<!! (async/into [] (tlog pull-output :pull-output))))))))

(deftest contiguous-seq-splitter-test
  (testing "contiguous-seq-splitter"
    (is (=
         [[[0 1] #{[:a 0 1] [:b 0 1]}]
          [[2 1] #{[:b 2 1] [:c 2 1]}]
          [[2 4] #{[:a 2 4]}]
          [[5 2] #{[:b 5 2]}]]
         (split-data-canonical-rep
          (mapcat second
                  (contiguous-seq-splitter
                   [[:a 0] [:b 0] [:a 2] [:b 2] [:c 2]
                    [:a 3] [:a 4] [:a 5] [:b 5] [:b 6]]
                   first
                   (per-group-stateful-transducer-creator
                    second inc 100000 (fn dbl [x] (* x 2))))))))
    (is (= [[:a 0 2] [:a 0 4] [:a 0 8] [:a 0 16] [:a 0 21]]
           (mapcat second
                   (contiguous-seq-splitter
                    [[:a 0] [:a 1] [:a 2] [:a 3] [:a 4] [:a 5]
                     [:a 6] [:a 7] [:a 8] [:a 9] [:a 10]
                     [:a 11] [:a 12] [:a 13] [:a 14] [:a 15]
                     [:a 16] [:a 17] [:a 18] [:a 19] [:a 20]]
                    first
                    (per-group-stateful-transducer-creator
                     second inc 2 (fn dbl [x] (* x 2)))))))))
