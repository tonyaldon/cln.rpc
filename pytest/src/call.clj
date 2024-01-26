(ns call
  (:require [cln-client-clj :as client])
  (:require [clojure.data.json :as json])
  (:require [com.brunobonacci.mulog :as u])
  (:require [clojure.edn :as edn])
  (:require [clojure.java.io :as io]))

(defn getinfo [{:keys [socket-file test-payload]}]
  (-> (if test-payload
        ;; to test we pass [] and not `null` in the json request
        ;; when `payload` argument of `call` in `nil`
        (client/call socket-file "getinfo" nil)
        (client/call socket-file "getinfo"))
      (json/write *out* :escape-slash false)))

(defn jsonrpc-id
  "Print the jsonrpc id used in the getinfo request to lightningd.
  SOCKET-FILE is lightningd's socket file."
  [{:keys [socket-file json-id-prefix]}]
  (let [log-file "/tmp/jsonrpc-id"]
    (io/delete-file log-file true)
    (def stop (u/start-publisher!
               {:type :simple-file :filename log-file}))
    (if json-id-prefix
      (client/call socket-file "getinfo" nil json-id-prefix)
      (client/call socket-file "getinfo"))
    (Thread/sleep 1000) ;; wait for log dispatch
    (stop)
    (with-open [in (java.io.PushbackReader. (io/reader log-file))]
      (loop [read-more true]
        (when-let [event (edn/read {:default tagged-literal :eof nil} in)]
          (if (= (:mulog/event-name event) :cln-client-clj/request-sent)
            (print (:req-id event))
            (recur true)))))))
