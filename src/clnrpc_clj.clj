(ns clnrpc-clj
  "Core Lightning JSON-RPC client."
  (:refer-clojure :exclude [read])
  (:require [clojure.data.json :as json])
  (:require [clojure.string :as str])
  (:require [com.brunobonacci.mulog :as u])
  (:require [babashka.fs :as fs])
  (:require [clojure.core.async :refer [>!!]])
  (:import java.nio.channels.SocketChannel
           java.net.StandardProtocolFamily
           java.net.UnixDomainSocketAddress
           java.nio.ByteBuffer
           java.nio.charset.StandardCharsets))

(defn read
  "Return the response sent by lightningd over SOCKET-CHANNEL.
  Also, close SOCKET-CHANNEL.

  If NOTIFS is a channel (from clojure.core.async) and we enabled
  (before) notifications for the JSON-RPC connection SOCKET-CHANNEL
  with `enable-nofications` queue in NOTIFS (with async/>!!)
  'message' and 'progress' notifications sent by lightningd.

  See CLN 'notifications' method."
  ([socket-channel]
   (read socket-channel nil))
  ([socket-channel notifs]
   (let [resp
         (loop [bb (ByteBuffer/allocate 1024)
                resp-acc ""]
           (if (str/includes? resp-acc "\n\n")
             (let [resps (str/split resp-acc #"\n\n")
                   resp-str (first resps)
                   resp (json/read-str resp-str :key-fn keyword)]
               (if (some #{:id} (keys resp)) ;; For some reason, (contains? resp :id) doesn't work here!
                 (do
                   (when notifs (>!! notifs :no-more))
                   resp)
                 (do
                   (when notifs (>!! notifs resp))
                   (recur bb (subs resp-acc (+ (count resp-str) 2))))))
             (do
               (.read socket-channel bb)
               (.flip bb)
               (let [bb-str (str (.decode StandardCharsets/UTF_8 bb))]
                 (.clear bb)
                 (recur bb (str resp-acc bb-str))))))]
     (.close socket-channel)
     resp)))

(defn enable-nofications
  "Enable notifications for the JSON-RPC connection SOCKET-CHANNEL."
  [socket-channel]
  ;; enable 'message' and 'progress' notifications
  (let [req {:jsonrpc "2.0"
             :method "notifications"
             :params {:enable true}
             :id "notify"}
        req-str (str (json/write-str req :escape-slash false) "\n\n")]
    (->> req-str .getBytes ByteBuffer/wrap (.write socket-channel)))
  (loop [bb (ByteBuffer/allocate 1024)
         resp-acc ""]
    (when-not (str/includes? resp-acc "\n\n")
      (.read socket-channel bb)
      (.flip bb)
      (let [bb-str (str (.decode StandardCharsets/UTF_8 bb))]
        (.clear bb)
        (recur bb (str resp-acc bb-str))))))

(defn symlink [target]
  (let [filename (str "lightning-rpc-" (subs (str (random-uuid)) 0 18))
        link-path (str (fs/file (fs/temp-dir) filename))]
    (fs/create-sym-link link-path target)
    link-path))

(defn connect-to [socket-file]
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
  "Send METHOD to lightningd which is called with PARAMS arguments if any.

  If no PARAMS, send with [] empty params.

  If FILTER specified, lightningd may filter the reponse.

  The connection is done via :socket-file specified in RPC-INFO.

  :json-id-prefix key of RPC-INFO is used as the first part of
  the JSON-RPC request id.  Default value is \"clnrpc-clj\".  For a
  getinfo call with :json-id-prefix being \"my-prefix\" the request
  id looks like this:

      my-prefix:getinfo/123

  Let's look at a few examples.

  Assuming we are running a node on regtest reachable via the socket file

      /tmp/l1-regtest/regtest/lightning-rpc

  we can do a getinfo request like this

      (call {:socket-file \"/tmp/l1-regtest/regtest/lightning-rpc\"}
            \"getinfo\")

  which returns:

      {:address [], :color \"023916\", ,,, , :id \"02391...387e4\", ,,,}

  We can let lightningd filter the response and return only the node id
  by specifying the filter argument like this

      (call {:socket-file \"/tmp/l1-regtest/regtest/lightning-rpc\"}
            \"getinfo\" nil {:id true})

  which gives us:

      {:id \"02391...387e4\"}

  We can create an invoice by calling

      (let [rpc-info {:socket-file \"/tmp/l1-regtest/regtest/lightning-rpc\"}
            params {:amount_msat 10000
                     :label (str \"label-\" (rand))
                     :description \"description\"}]
        (call rpc-info \"invoice\" params))

  which returns:

       {:payment_hash \"d9704...a27b4\",
        :expires_at 1707475596,
        :bolt11 \"lnbcrt100n1...0t5v8z\",
        :payment_secret \"ef66b...5be58\",
        :created_index 5}

  We can let lightningd filter the response and return only the bolt11
  invoice string by specifying the filter argument like this

      (let [,,,]
        (call rpc-info \"invoice\" params {:bolt11 true}))

  which gives us:

      {:bolt11 \"lnbcrt100n1...0t5v8z\"}

  If for some reason lightningd can't process our request, raise a
  clojure.lang.ExceptionInfo exception.  We can catch and return
  the \"error\" field of lightningd's response like this:

      (try
       (call {:socket-file \"/tmp/l1-regtest/regtest/lightning-rpc\"} \"foo\")
       (catch clojure.lang.ExceptionInfo e
         (when (= (:type (ex-data e)) :rpc-error)
           (:error (ex-data e)))))

      ;; {:code -32601, :message \"Unknown command 'foo'\"}

  Some commands may send 'message' or 'progress' notifications
  while they are processing the request.  clnrpc-clj can receive those
  notifications.  To enable this, we just have to pass a channel
  (from clojure.core.async) in RPC-INFO argument as value of :notifs
  key.  The notifications will then be queued (with `>!!`) in that
  channel.

  To understand how to process those notifications, let's consider
  the following example.

  We start the plugin notify.py

      #!/usr/bin/env python3

      from pyln.client import Plugin
      import time

      plugin = Plugin()

      @plugin.method(\"send-message-notifications\")
      def send_message_notifications(plugin, request, **kwargs):
          request.notify(\"foo\")
          time.sleep(0.5)
          request.notify(\"bar\")
          time.sleep(0.5)
          request.notify(\"baz\")
          return {\"foo\": \"bar\"}

      plugin.run()

  on the node l1 running on regtest like this
  (lightning directory contains lightning repository):

      $ source lightning/contrib/startup_regtest.sh
      $ python -m venv .venv
      $ source env/bin/activate
      $ chmod +x notify.py
      $ start_ln
      $ l1-cli plugin start $(pwd)/notify.py

  If we use lightning-cli (l1-cli) to call send-message-notifications,
  we get the following:

      $ l1-cli send-message-notifications
      # foo
      # bar
      # baz
      {
          \"foo\": \"bar\"
      }

  Now let's do it with clnrpc-clj.

  We define a channel notifs that we pass to call function that will
  queue the notifications sent by notify.py plugin before returning
  the response to our send-message-notifications request.  We do that
  in a go block so that we can dequeue those notifications in a loop
  where we accumulate them in a vector and in which we also append
  the response.  Finally, we return that array:

      (require '[clnrpc-clj :as rpc])
      (require '[clojure.core.async :refer [<!! go chan]])

      (let [notifs (chan)
            rpc-info {:socket-file \"/tmp/l1-regtest/regtest/lightning-rpc\"
                      :notifs notifs}
            resp (go (rpc/call rpc-info \"send-message-notifications\"))
            notifs-and-resp (atom [])]
        (loop [notif (<!! notifs)]
          (if (= notif :no-more)
            (swap! notifs-and-resp conj (<!! resp))
            (do
              (swap! notifs-and-resp conj (get-in notif [:params :message]))
              (recur (<!! notifs)))))
        @notifs-and-resp)

      ;; [\"foo\" \"bar\" \"baz\" {:foo \"bar\"}]"
  ([rpc-info method]
   (call rpc-info method [] nil))
  ([rpc-info method params]
   (call rpc-info method params nil))
  ([rpc-info method params filter]
   (let [channel (connect-to (:socket-file rpc-info))
         notifs (:notifs rpc-info)
         req-id (format "%s:%s#%s"
                        (or (:json-id-prefix rpc-info) "clnrpc-clj")
                        method (int (rand 100000)))
         req (merge {:jsonrpc "2.0"
                     :method method
                     :params (or params [])
                     :id req-id}
                    (or (and filter {:filter filter}) nil))
         req-str (json/write-str req :escape-slash false)]
     (when notifs (enable-nofications channel))
     (->> req-str .getBytes ByteBuffer/wrap (.write channel))
     (u/log ::request-sent :req req :req-id req-id :req-str req-str)
     (let [resp (read channel notifs)
           resp-id (:id resp)]
       (if (= resp-id req-id)
         (if-let [error (:error resp)]
           (throw
            (ex-info
             (format (format "RPC error: %s" (or (:message error) "")))
             {:type :rpc-error :error error :req req :resp resp}))
           (:result resp))
         (throw
          (ex-info
           (format "Incorrect 'id' %s in response: %s.  The request was: %s"
                   resp-id resp req)
           {:resp-id resp-id :req-id req-id :resp resp :req req})))))))

(load "rpcmethods")
