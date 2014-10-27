(ns onyx-timeline-example.onyx.component
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [onyx.peer.task-lifecycle-extensions :as l-ext]
            [onyx.plugin.core-async]
            [onyx.api]))

;;;;; Implementation functions ;;;;;
(defn split-by-spaces-impl [s]
  (clojure.string/split s #"\s+"))

(defn mixed-case-impl [s]
  (->> (cycle [(memfn toUpperCase) (memfn toLowerCase)])
       (map #(%2 (str %1)) s)
       (apply str)))

(defn loud-impl [s]
  (str s "!"))

(defn question-impl [s]
  (str s "?"))

;;;;; Destructuring functions ;;;;;
(defn split-by-spaces [segment]
  (map (fn [word] {:word word}) (split-by-spaces-impl (:sentence segment))))

(defn mixed-case [segment]
  {:word (mixed-case-impl (:word segment))})

(defn loud [segment]
  {:word (loud-impl (:word segment))})

(defn question [segment]
  {:word (question-impl (:word segment))})

;;;;; Configuration ;;;;;

;;;            input
;;;              |
;;;       split-by-spaces
;;;              |
;;;          mixed-case
;;;            /    \
;;;         loud     question
;;;           |         |
;;;    loud-output    question-output

(def workflow
  {:input
   {:split-by-spaces
    {:mixed-case
     {:loud :loud-output}}}})


(def batch-size 1)

(def catalog
  [{:onyx/name :input
    :onyx/ident :core.async/read-from-chan
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :split-by-spaces
    :onyx/fn :onyx-timeline-example.onyx.component/split-by-spaces
    :onyx/type :transformer
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size}

   {:onyx/name :mixed-case
    :onyx/fn :onyx-timeline-example.onyx.component/mixed-case
    :onyx/type :transformer
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size}

   {:onyx/name :loud
    :onyx/fn :onyx-timeline-example.onyx.component/loud
    :onyx/type :transformer
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size}

   {:onyx/name :loud-output
    :onyx/ident :core.async/write-to-chan
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/doc "Writes segments to a core.async channel"}])

;;; Input data to pipe into the input channel, plus the
;;; sentinel to signal the end of input.
; (def input-segments
;   [{:sentence "Hey there user"}
;    {:sentence "It's really nice outside"}
;    {:sentence "I live in Redmond"}
;    :done])

;;; Put the data onto the input chan
; (doseq [segment input-segments]
;   (>!! input-chan segment))
; (close! input-chan)

(def id (java.util.UUID/randomUUID))

(def coord-opts
  {:hornetq/mode :vm ;; Run HornetQ inside the VM for convenience
   :hornetq/server? true
   :hornetq.server/type :vm
   :zookeeper/address "127.0.0.1:2185"
   :zookeeper/server? true ;; Run ZK inside the VM for convenience
   :zookeeper.server/port 2185
   :onyx/id id
   :onyx.coordinator/revoke-delay 5000})

(def peer-opts
  {:hornetq/mode :vm
   :zookeeper/address "127.0.0.1:2185"
   :onyx/id id})

(def capacity 1000)

(defrecord Onyx [conf input-chans output-chan conn v-peers]
  component/Lifecycle
  (start [component] (log/info "Starting Onyx Component")
    (let [input-chan (:producer input-chans)
          output-chan (chan capacity)]
      (println "Input chan was " input-chan " output " output-chan)
      ;;; Inject the channels needed by the core.async plugin for each
      ;;; input and output.
      (defmethod l-ext/inject-lifecycle-resources :input
        [_ _] {:core-async/in-chan input-chan})

      (defmethod l-ext/inject-lifecycle-resources :loud-output
        [_ _] {:core-async/out-chan output-chan})

      (let [conn (onyx.api/connect :memory coord-opts)
            v-peers (onyx.api/start-peers conn 1 peer-opts)]
        (onyx.api/submit-job conn {:catalog catalog :workflow workflow})
        (assoc component 
               :input-chan input-chan
               :output-chan output-chan
               :conn conn
               :v-peers v-peers))))
  (stop [component] 
    (log/info "Stop Onyx Component")
    (doseq [v-peer v-peers]
      ((:shutdown-fn v-peer)))
    (onyx.api/shutdown conn)))

;; A little utility to read from the channel until :done
; (defn take-segments! [ch]
;   (loop [x []]
;     (let [segment (<!! ch)]
;       (let [stack (conj x segment)]
;         (if-not (= segment :done)
;           (recur stack)
;           stack)))))

;(def loud-results (take-segments! loud-output-chan))
;
;(def question-results (take-segments! question-output-chan))

;(clojure.pprint/pprint loud-results)
;
;(println)
;
;(clojure.pprint/pprint question-results)

(defn new-onyx-server [conf] (map->Onyx {:conf conf}))