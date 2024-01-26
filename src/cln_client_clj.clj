(ns cln-client-clj
  "Core Lightning JSON-RPC client."
  (:refer-clojure :exclude [read])
  (:require [clojure.data.json :as json])
  (:require [clojure.string :as str])
  (:require [com.brunobonacci.mulog :as u])
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

  If no PAYLOAD, call with empty [] payload.

  JSON-ID-PREFIX string is used as the first part of the JSON-RPC
  request id.  Default value is \"cln-client-clj\".  For a
  getinfo call with JSON-ID-PREFIX being \"my-prefix\" the request
  id looks like this:

      my-prefix:getinfo/123"
  ([socket-file method]
   (call socket-file method []))
  ([socket-file method payload]
   (call socket-file method payload "cln-client-clj"))
  ([socket-file method payload json-id-prefix]
   (let [channel (connect socket-file)
         req-id (format "%s:%s#%s" json-id-prefix method (int (rand 100000)))
         req {:jsonrpc "2.0"
              :method method
              :params (or payload [])
              :id req-id}
         req-str (json/write-str req :escape-slash false)]
     (->> req-str .getBytes ByteBuffer/wrap (.write channel))
     (u/log ::request-sent :req req :req-id req-id :req-str req-str)
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
