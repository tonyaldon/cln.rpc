(ns clnrpc-clj-test
  "Test clnrpc-clj library."
  (:require [clojure.test :refer :all])
  (:require [clnrpc-clj :as rpc])
  (:require [clojure.java.shell :refer [sh]])
  (:import java.io.File)
  (:require [babashka.fs :as fs])
  (:require [clojure.data.json :as json])
  (:require [clojure.core.async :as a :refer [<!! chan]]))

(deftest read-test
  (is (=
       (let [msg (format "%s\n\ndiscarded" (json/write-str {:id "id"}))
             socket-file (str (File/createTempFile "socket-file-" nil))
             send-msg-cmd (format "echo '%s' | nc -U %s -l" msg socket-file)]
         ;; start socket server and send `msg`
         (.start (Thread. (fn [] (sh "bash" "-c" send-msg-cmd))))
         (Thread/sleep 1000) ;; wait for socket server to start
         (let [socket-channel (rpc/connect-to socket-file)
               resp (rpc/read socket-channel)]
           (.close socket-channel)
           resp))
       {:id "id"}))
  (is (=
       (let [msg (format "%s\n\n%s\n\n%s\n\ndiscarded"
                         (json/write-str {:no-id "notif-1"})
                         (json/write-str {:no-id "notif-2"})
                         (json/write-str {:id "id"}))
             socket-file (str (File/createTempFile "socket-file-" nil))
             send-msg-cmd (format "echo '%s' | nc -U %s -l" msg socket-file)]
         ;; start socket server and send `msg`
         (.start (Thread. (fn [] (sh "bash" "-c" send-msg-cmd))))
         (Thread/sleep 1000) ;; wait for socket server to start
         (let [socket-channel (rpc/connect-to socket-file)
               resp (rpc/read socket-channel)]
           (.close socket-channel)
           resp))
       {:id "id"}))
  (is (=
       (let [msg (format "%s\n\n%s\n\n%s\n\ndiscarded"
                         (json/write-str {:no-id "notif-1"})
                         (json/write-str {:no-id "notif-2"})
                         (json/write-str {:id "id"}))
             socket-file (str (File/createTempFile "socket-file-" nil))
             send-msg-cmd (format "echo '%s' | nc -U %s -l" msg socket-file)]
         ;; start socket server and send `msg`
         (.start (Thread. (fn [] (sh "bash" "-c" send-msg-cmd))))
         (Thread/sleep 1000) ;; wait for socket server to start
         (let [resp-and-notifs (atom nil)
               notifs (chan)
               socket-channel (rpc/connect-to socket-file)
               resp (future (rpc/read socket-channel notifs))]
           (loop [notif (<!! notifs)]
             (if (= notif :no-more)
               (swap! resp-and-notifs conj @resp)
               (do
                 (swap! resp-and-notifs conj notif)
                 (recur (<!! notifs)))))
           (.close socket-channel)
           @resp-and-notifs))
       '({:id "id"} {:no-id "notif-2"} {:no-id "notif-1"}))))

(deftest call-test
  ;; test that we raise an error if we receive a response
  ;; from lightningd with an id that don't match the one we send
  ;; in our request
  (is (thrown-with-msg?
       Throwable
       #"Incorrect 'id' .+ in response: .+\.  The request was: .+"
       (let [msg-wrong-id "{\"jsonrpc\":\"2.0\",\"id\":\"WRONG-ID\",\"result\": []}\n\n"
             socket-file (str (File/createTempFile "socket-file-" nil))
             send-msg-cmd (format "echo '%s' | nc -U %s -l" msg-wrong-id socket-file)
             rpc-info {:socket-file socket-file}]
         ;; start socket server and send `msg-wrong-id`
         (.start (Thread. (fn [] (sh "bash" "-c" send-msg-cmd))))
         (Thread/sleep 1000) ;; wait for socket server to start
         (rpc/call rpc-info "getinfo")))))

(deftest symlink-test
  (let [target (str (fs/create-temp-file))
        link (rpc/symlink target)]
    (is (fs/same-file? link target))
    (fs/delete link)
    (fs/delete target)))
