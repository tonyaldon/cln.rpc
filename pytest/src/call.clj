(ns call
  (:require [cln-client-clj :as client])
  (:import java.nio.ByteBuffer))

(defn getinfo [{:keys [socket-file]}]
  (print (:id (client/call socket-file "getinfo"))))
