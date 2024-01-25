(ns call
  (:require [cln-client-clj :as client])
  (:require [clojure.data.json :as json])
  (:import java.nio.ByteBuffer))

(defn getinfo [{:keys [socket-file]}]
  (-> (client/call socket-file "getinfo")
      (json/write *out* :escape-slash false)))
