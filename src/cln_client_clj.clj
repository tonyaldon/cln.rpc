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
   (call socket-file method []))
  ([socket-file method payload]
   (let [channel (connect socket-file)
         req-id "1"
         req {:jsonrpc "2.0"
              :method method
              :params payload
              :id req-id}]
     (->> (json/write-str req :escape-slash false)
          (.getBytes)
          ByteBuffer/wrap
          (.write channel))
     (let [resp (-> (read channel) (json/read-str :key-fn keyword))
           resp-id (:id resp)]
       (if (= resp-id req-id)
         (:result resp)
         (throw
          (ex-info
           (format "Incorrect 'id' %s in response: %s.  The request was: %s"
                   resp-id resp req)
           {:resp-id resp-id
            :req-id req-id
            :resp resp
            :req req})))))))


(comment
  (call "/tmp/l1-regtest/regtest/lightning-rpc" "getinfo"))
