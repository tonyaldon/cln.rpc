(ns rpc
  (:require [clnrpc-clj :as rpc])
  (:require [clojure.data.json :as json])
  (:require [com.brunobonacci.mulog :as u])
  (:require [clojure.edn :as edn])
  (:require [clojure.java.io :as io]))

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
  (let [rpc-info {:socket-file socket-file}]
    (-> (rpc/call rpc-info "getinfo" nil {:id true})
        (json/write *out* :escape-slash false))))

(defn call-invoice-with-filter [{:keys [socket-file]}]
  (let [rpc-info {:socket-file socket-file}
        params {:amount_msat 10000
                :label "invoice-with-filter"
                :description "description"}]
    (-> (rpc/call rpc-info "invoice" params {:bolt11 true})
        (json/write *out* :escape-slash false))))

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
          (if (= (:mulog/event-name event) :clnrpc-clj/request-sent)
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

(comment
  (rpc/newaddr {:socket-file "/tmp/l1-regtest/regtest/lightning-rpc"} :addresstype "p2tr")
  (newaddr {:socket-file "/tmp/l1-regtest/regtest/lightning-rpc"
            :addresstype "p2tr"})
  (invoice {:socket-file "/tmp/l1-regtest/regtest/lightning-rpc"
            :expiry 3600
            :cltv 8})
  (invoice {:socket-file "/tmp/l1-regtest/regtest/lightning-rpc"
            :expiry 3600
            :cltv 8})
  (decode {:socket-file "/tmp/l1-regtest/regtest/lightning-rpc"
           :string "lnbcrt100n1pjukvvfsp5nedu4x4vfz5e4wzsncwdvxl40pagx4fqar6kwyz6uqeyjm0szuwqpp5exhle30pr9z36d6mz0kjrxtmdvumvtzts0dqv4jnktpplzhkv3fsdqjv3jhxcmjd9c8g6t0dccqpgfp4pc5jhwpvx34krcpzlzs5mv647uvy5y66ful2erkyte5q744ayagaq9qx3qysgq7le7cmzj6n22ujpyy9psdk0qa072fhjtqxm6psdj7y23wx5nydk8pkjft09ms2z0pqhh9g9xfqrjyrnxdamahpzcrxq3z4jl9zpgkhqq6tsyr8"
           }))
