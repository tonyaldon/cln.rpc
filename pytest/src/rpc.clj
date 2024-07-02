(ns rpc
  (:require [tonyaldon.cln.rpc.core :as rpc])
  (:require [clojure.data.json :as json])
  (:require [com.brunobonacci.mulog :as u])
  (:require [clojure.edn :as edn])
  (:require [clojure.java.io :as io])
  (:require [clojure.core.async :refer [<!! go chan]]))

(defn call-getinfo [{:keys [socket-file test-params]}]
  (let [rpc-info {:socket-file socket-file}]
    (-> (if test-params
          ;; to test we pass [] and not `null` in the json request
          ;; when `params` argument of `call` is `nil`
          (rpc/call rpc-info "getinfo" nil)
          (rpc/call rpc-info "getinfo"))
        (json/write *out* :escape-slash false))))

(defn call-invoice [{:keys [socket-file]}]
  (let [rpc-info {:socket-file socket-file}
        params {:amount_msat 10000
                :label (str "label-" (rand))
                :description "description"}]
    (-> (rpc/call rpc-info "invoice" params)
        :bolt11
        print)))

(defn call-pay [{:keys [socket-file bolt11]}]
  (let [rpc-info {:socket-file socket-file}
        params {:bolt11 bolt11}]
    (-> (rpc/call rpc-info "pay" params)
        :status
        print)))

(defn call-unknown-command-foo [{:keys [socket-file]}]
  (let [rpc-info {:socket-file socket-file}]
    (try
      (rpc/call rpc-info "foo")
      (catch clojure.lang.ExceptionInfo e
        (when (= (:type (ex-data e)) :rpc-error)
          (-> (ex-data e)
              :error
              (json/write *out* :escape-slash false)))))))

(defn call-invoice-missing-label [{:keys [socket-file]}]
  (let [rpc-info {:socket-file socket-file}
        params {:amount_msat 10000}]
    (try
      (rpc/call rpc-info "invoice" params)
      (catch clojure.lang.ExceptionInfo e
        (when (= (:type (ex-data e)) :rpc-error)
          (-> (ex-data e)
              :error
              (json/write *out* :escape-slash false)))))))

(defn call-getinfo-with-filter [{:keys [socket-file]}]
  (let [rpc-info {:socket-file socket-file
                  :filter {:id true}}]
    (-> (rpc/call rpc-info "getinfo")
        (json/write *out* :escape-slash false))))

(defn call-invoice-with-filter [{:keys [socket-file]}]
  (let [rpc-info {:socket-file socket-file
                  :filter {:bolt11 true}}
        params {:amount_msat 10000
                :label "invoice-with-filter"
                :description "description"}]
    (-> (rpc/call rpc-info "invoice" params)
        (json/write *out* :escape-slash false))))

(defn call-send-message-notifications-with-enable-notifications
  [{:keys [socket-file]}]
  (let [notifs (chan)
        rpc-info {:socket-file socket-file
                  :notifs notifs}
        resp (go (rpc/call rpc-info "send-message-notifications"))
        notifs-and-resp (atom [])]
    (loop [notif (<!! notifs)]
      (if (= notif :no-more)
        (swap! notifs-and-resp conj (<!! resp))
        (do
          (swap! notifs-and-resp conj (get-in notif [:params :message]))
          (recur (<!! notifs)))))
    (json/write @notifs-and-resp *out* :escape-slash false)))

(defn call-send-message-notifications-without-enable-notifications
  [{:keys [socket-file]}]
  (let [rpc-info {:socket-file socket-file}]
    (-> (rpc/call rpc-info "send-message-notifications")
        (json/write *out* :escape-slash false))))

(defn call-send-progress-notifications-with-enable-notifications
  [{:keys [socket-file]}]
  (let [notifs (chan)
        rpc-info {:socket-file socket-file
                  :notifs notifs}
        resp (go (rpc/call rpc-info "send-progress-notifications"))
        notifs-and-resp (atom [])]
    (loop [notif (<!! notifs)]
      (if (= notif :no-more)
        (swap! notifs-and-resp conj (<!! resp))
        (do
          (swap! notifs-and-resp conj (get-in notif [:params :num]))
          (recur (<!! notifs)))))
    (json/write @notifs-and-resp *out* :escape-slash false)))

(defn call-send-progress-notifications-without-enable-notifications
  [{:keys [socket-file]}]
  (let [rpc-info {:socket-file socket-file}]
    (-> (rpc/call rpc-info "send-progress-notifications")
        (json/write *out* :escape-slash false))))

(defn call-getinfo-with-enable-notifications
  [{:keys [socket-file]}]
  (let [notifs (chan)
        rpc-info {:socket-file socket-file
                  :notifs notifs}
        resp (go (rpc/call rpc-info "getinfo"))
        notifs-and-resp (atom [])]
    (loop [notif (<!! notifs)]
      (if (= notif :no-more)
        (swap! notifs-and-resp conj (<!! resp))
        (do
          (swap! notifs-and-resp conj (get-in notif [:params :message]))
          (recur (<!! notifs)))))
    (json/write @notifs-and-resp *out* :escape-slash false)))

(defn jsonrpc-id
  "Print the jsonrpc id used in the getinfo request to lightningd.
  SOCKET-FILE is lightningd's socket file."
  [{:keys [socket-file json-id-prefix]}]
  (let [log-file "/tmp/jsonrpc-id"
        rpc-info {:socket-file socket-file
                  :json-id-prefix json-id-prefix}]
    (io/delete-file log-file true)
    (def stop (u/start-publisher!
               {:type :simple-file :filename log-file}))
    (rpc/call rpc-info "getinfo")
    (Thread/sleep 1000) ;; wait for log dispatch
    (stop)
    (with-open [in (java.io.PushbackReader. (io/reader log-file))]
      (loop [read-more true]
        (when-let [event (edn/read {:default tagged-literal :eof nil} in)]
          (if (= (:mulog/event-name event) :tonyaldon.cln.rpc.core/request-sent)
            (print (:req-id event))
            (recur true)))))))

;; params: 0 required / 0 optional
(defn getinfo [{:keys [socket-file]}]
  (let [rpc-info {:socket-file socket-file}]
    (-> (rpc/getinfo rpc-info)
        (json/write *out* :escape-slash false))))

;; params: 0 required / 1 optional
(defn newaddr [{:keys [socket-file addresstype]}]
  (let [rpc-info {:socket-file socket-file}]
    (-> (if addresstype
          (rpc/newaddr rpc-info :addresstype addresstype)
          (rpc/newaddr rpc-info))
        (json/write *out* :escape-slash false))))

;; params: 1 required / 0 optional
(defn decode [{:keys [socket-file string]}]
  (let [rpc-info {:socket-file socket-file}]
    (-> (rpc/decode rpc-info string)
        (json/write *out* :escape-slash false))))

;; params: 3 required / 6 optional
(defn invoice [{:keys [socket-file expiry cltv]}]
  (let [rpc-info {:socket-file socket-file}]
    (-> (if (and expiry cltv)
          (rpc/invoice rpc-info
                       10000 (str "label-" (rand)) "description"
                       :expiry expiry :cltv cltv)
          (rpc/invoice rpc-info
                       10000 (str "label-" (rand)) "description"))
        (json/write *out* :escape-slash false))))
