(ns cln-client-clj-test
  "Test cln-client-clj library."
  (:require [clojure.test :refer :all])
  (:require [cln-client-clj :as client])
  (:require [clojure.java.shell :refer [sh]])
  (:require [clojure.java.io :as io]))

(deftest read-test
  (is (=
       (let [msg "foo\nbar\nbaz\n\ndiscarded"
             socket-file "/tmp/socket-file"
             send-msg-cmd (format "echo '%s' | nc -U %s -l" msg socket-file)
             _ (doto (Thread. (fn []
                                (io/delete-file socket-file true)
                                (sh "bash" "-c" send-msg-cmd)))
                 .start)
             channel (do (Thread/sleep 1000) ;; wait for socket server to start
                         (client/connect socket-file))
             read (client/read channel)]
         read)
       "foo\nbar\nbaz")))

;; (run-tests 'cln-client-clj-test)
