(ns clnrpc-clj
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

(defn symlink [target]
  (let [tmpdir (System/getProperty "java.io.tmpdir")
        link-path (->>
                   (subs (.toString (random-uuid)) 0 18)
                   (str "lightning-rpc-")
                   vector
                   (into-array String)
                   (java.nio.file.Paths/get tmpdir))]
    (java.nio.file.Files/createSymbolicLink
     link-path
     (java.nio.file.Paths/get target (into-array String []))
     (into-array java.nio.file.attribute.FileAttribute []))
    (.toString link-path)))

(defn connect [socket-file]
  (let [channel (SocketChannel/open (StandardProtocolFamily/UNIX))]
    (try
      (.connect channel (UnixDomainSocketAddress/of socket-file))
      channel
      (catch Exception e
        (.close channel)
        (if (= (.getMessage e) "Unix domain path too long")
          (let [channel (SocketChannel/open (StandardProtocolFamily/UNIX))]
            (->>
             (symlink socket-file)
             UnixDomainSocketAddress/of
             (.connect channel))
            channel)
          (throw e))))))

(defn call
  "Send METHOD to lightningd which is called with PAYLOAD arguments if any.

  If no PAYLOAD, send with [] empty payload.

  The connection is done via :socket-file specified in RPC-INFO.

  :json-id-prefix key of RPC-INFO is used as the first part of
  the JSON-RPC request id.  Default value is \"clnrpc-clj\".  For a
  getinfo call with :json-id-prefix being \"my-prefix\" the request
  id looks like this:

      my-prefix:getinfo/123"
  ([rpc-info method]
   (call rpc-info method []))
  ([rpc-info method payload]
   (let [channel (connect (:socket-file rpc-info))
         req-id (format "%s:%s#%s"
                        (or (:json-id-prefix rpc-info) "clnrpc-clj")
                        method (int (rand 100000)))
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
         (if-let [error (:error resp)]
           (throw
            (ex-info
             (format (format "RPC error: %s" (or (:message error) "")))
             {:type :rpc-error
              :error error
              :req req
              :resp resp}))
           (:result resp))
         (throw
          (ex-info
           (format "Incorrect 'id' %s in response: %s.  The request was: %s"
                   resp-id resp req)
           {:resp-id resp-id
            :req-id req-id
            :resp resp
            :req req})))))))

(comment
  (call {:socket-file "/tmp/l1-regtest/regtest/lightning-rpc"} "getinfo")
  (call {:socket-file "/tmp/l1-regtest/regtest/lightning-rpc"} "foo")
  )
