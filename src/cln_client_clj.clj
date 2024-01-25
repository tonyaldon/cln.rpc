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
