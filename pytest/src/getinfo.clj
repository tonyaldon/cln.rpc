(ns getinfo
  (:require [cln-client-clj :as client])
  (:import java.nio.ByteBuffer))

(defn main [{:keys [socket-file]}]
  (print (:id (client/call socket-file "getinfo"))))
