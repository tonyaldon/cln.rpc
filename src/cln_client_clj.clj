(ns cln-client-clj
  "Core Lightning JSON-RPC client."
  (:refer-clojure :exclude [read])
  (:require [clojure.data.json :as json])
  (:require [clojure.string :as str])
  (:import java.nio.channels.SocketChannel
           java.net.StandardProtocolFamily
           java.net.UnixDomainSocketAddress
           java.nio.ByteBuffer
           java.nio.charset.StandardCharsets))

(defn read
  "Return the response sent by lightningd over SOCKET-CHANNEL.
  Also, close SOCKET-CHANNEL."
  [socket-channel]
  (let [resp
        (loop [bb (ByteBuffer/allocate 1024)
               resp-acc ""]
          (.read socket-channel bb)
          (.flip bb)
          (let [bb-str (.toString (.decode StandardCharsets/UTF_8 bb))
                r (str resp-acc bb-str)]
            (if (str/includes? r "\n\n")
              (first (str/split r #"\n\n"))
              (do
                (.clear bb)
                (recur bb r)))))]
    (.close socket-channel)
    resp))

(defn connect [socket-file]
  (let [channel (SocketChannel/open (StandardProtocolFamily/UNIX))]
    (.connect channel (UnixDomainSocketAddress/of socket-file))
    channel))

(defn call
  "Call METHOD with PAYLOAD in lightningd via SOCKET-FILE.
  If no PAYLOAD, call with empty [] payload."
  ([socket-file method]
   (str socket-file " " method)
   (call socket-file method []))
  ([socket-file method payload]
   (let [channel (connect socket-file)
         req {:jsonrpc "2.0"
              :method method
              :params payload
              :id "1"}]
     (->> (json/write-str req :escape-slash false)
          (.getBytes)
          ByteBuffer/wrap
          (.write channel))
     (:result (json/read-str (read channel) :key-fn keyword)))))


(comment
  (call "/tmp/l1-regtest/regtest/lightning-rpc" "getinfo"))
