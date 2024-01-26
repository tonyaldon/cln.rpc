(ns call
  (:require [cln-client-clj :as client])
  (:require [clojure.data.json :as json])
  (:import java.nio.ByteBuffer))

(defn getinfo [{:keys [socket-file test-payload]}]
  (-> (if test-payload
        ;; to test we pass [] and not `null` in the json request
        ;; when `payload` argument of `call` in `nil`
        (client/call socket-file "getinfo" nil)
        (client/call socket-file "getinfo"))
      (json/write *out* :escape-slash false)))
