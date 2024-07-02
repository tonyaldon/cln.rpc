(ns gen-rpcmethods
  "Generate functions from files in schemas CLN directory.

  Each function generated can be used to do RPC calls to CLN for a specific
  method.

  We generate those functions and store them in rpcmethods.clj file.
  They are available in tonyaldon.cln.rpc.core namespace."
  (:require [clojure.data.json :as json])
  (:require [clojure.string :as str])
  (:require [zprint.core :as zp])
  (:require [clojure.java.io :as io])
  (:require [clojure.java.shell :refer [sh *sh-dir*]])
  (:require [babashka.fs :as fs]))

(defn docstring-type-description [{:keys [type description]}]
  (format "      - type: %s
      - description:%s"
          type
          (or
           (and (vector? description) (not (empty? description))
                (str " " (str/join "\n      " description)))
           (and description (not (empty? description))
                (str " " description))
           "")))

(defn docstring-type [req? type-name type-map]
  (format "  %s\n%s"
          (if req? (str/upper-case type-name) (keyword type-name))
          (if (contains? type-map :oneOf)
            (do
              (str/join "\n\n      or\n\n" (map docstring-type-description (:oneOf type-map))))
            (docstring-type-description type-map))))

(defn docstring [method schema]
  (let [{:keys [required properties]} schema
        description (format "Send %s request to lightningd via unix socket.

  The connection is done via :socket-file specified in RPC-INFO.
  :json-id-prefix key of RPC-INFO is used as the first part of
  the JSON-RPC request id.  See tonyaldon.cln.rpc.core/call for more details."
                            method)
        required-params
        (->>
         required
         (map #(docstring-type true % ((keyword %) properties)))
         (str/join "\n\n"))
        opt-params-sentence "\n\n  Use OPT-PARAMS to set optional parameters of the request.
  The following keyword argument(s) can be passed with values:\n\n"
        opt-params
        (->>
         (keys (seq properties))
         (filter #(not (.contains required (name %))))
         (map #(docstring-type false (name %) (% properties)))
         (str/join "\n\n"))]
    (str description
         (when-not (empty? required)
           (str "\n\n" required-params))
         (when-not (empty? opt-params)
           (str opt-params-sentence opt-params)))))

(defn generate-rpcmethod [method schema]
  (let [{:keys [required properties]} schema
        docstring (docstring method schema)]
    (if-let [required-map
             (->> required
                  (map #(identity {(keyword %) (symbol %)}))
                  (apply merge))]
      (if (= (count required) (count properties))
        `(~'defn ~(symbol method)
          ~docstring
          [~'rpc-info ~@(map symbol required)]
          (~'call ~'rpc-info ~method ~required-map))
        `(~'defn ~(symbol method)
          ~docstring
          [~'rpc-info ~@(map symbol required) & ~'opt-params]
          (~'call ~'rpc-info
           ~method
           (~'merge ~required-map (~'apply ~'hash-map ~'opt-params)))))
      (if (empty? properties)
        `(~'defn ~(symbol method)
          ~docstring
          [~'rpc-info]
          (~'call ~'rpc-info ~method))
        `(~'defn ~(symbol method)
          ~docstring
          [~'rpc-info & ~'opt-params]
          (~'call ~'rpc-info ~method (~'apply ~'hash-map ~'opt-params)))))))

(defn def-rpcmethod [method schema]
  (eval (generate-rpcmethod method schema)))

(defn git-checkout-lightning [tag]
  (when-not (and (fs/exists? "lightning")
                 (->> (binding [*sh-dir* "./lightning"]
                        (:out (sh "git" "tag")))
                      str/split-lines
                      (some #(= % tag))))
    (sh "rm" "-rf" "lightning")
    (println "git clone https://github.com/ElementsProject/lightning")
    (sh "git" "clone" "https://github.com/ElementsProject/lightning"))
  (let [checkout (binding [*sh-dir* "./lightning"]
                   (sh "git" "checkout" tag))]
    (if-not (zero? (:exit checkout))
      (throw
       (ex-info
        (format "Cannot checkout to tag %s in lightning repository" tag)
        {:checkout checkout})))))

(defn list-rpcmethods [schemas-dir]
  (let [files (map str (fs/list-dir schemas-dir))
        ;; See ShahanaFarooqui/docs-consolidation branch.
        ;; Should be merged for CLN v24.05.
        new-schema-type? (some #(= (fs/file-name %) "lightning-getinfo.json") files)
        schema-files
        (->> files
             (filter
              #(str/includes? % (if new-schema-type? "lightning-" ".request.json")))
             sort)]
    (map #(let [schema (with-open [rdr (io/reader %)]
                         (json/read rdr :key-fn keyword))]
            (if new-schema-type?
              [(second (re-find #"lightning-(.*)\.json" (fs/file-name %)))
               (:request schema)]
              [(first (str/split (fs/file-name %) #"\."))
               (select-keys schema [:required :properties])]))
         schema-files)))

(defn generate-rpcmethods-str [rpcmethods]
  (->> rpcmethods
       (map #(apply generate-rpcmethod %))
       (map pr-str)
       (map #(zp/zprint-str % {:parse-string? true}))
       (str/join "\n\n")
       (#(str/replace % "\\n" "\n"))))

(defn write-rpcmethods-to-file [file tag rpcmethods]
  (let [content
        (str
         ";; CLN " tag " - RPC methods generated from doc/schemas directory\n"
         ";;
;; Do not edit this file directly.
;; See clnrpc-utils/generate-rpcmethods.\n\n"
         "(in-ns 'tonyaldon.cln.rpc.core)\n\n"
         (zp/zprint-str
          `(~'def ~'rpcmethods ~(mapv first rpcmethods)))
         "\n\n"
         (generate-rpcmethods-str rpcmethods)
         "\n")]
    (spit file content)))

(defn generate-rpcmethods [{:keys [tag]}]
  (git-checkout-lightning tag)
  (let [rpcmethods (list-rpcmethods "lightning/doc/schemas")
        file "src/tonyaldon/cln/rpc/rpcmethods.clj"]
    (print (format "Generate CLN RPC methods %s in %s..." tag file))
    (write-rpcmethods-to-file file tag rpcmethods)
    (println "done")))

;; (generate-rpcmethods {:tag "v23.11"})

(comment
  (require '[tonyaldon.cln.rpc.core :refer [call]])

  (def rpc-info-regtest {:socket-file "/tmp/l1-regtest/regtest/lightning-rpc"})
  (ns-unmap *ns* 'invoice)
  (def-rpcmethod "invoice" invoice-schema)
  (invoice rpc-info-regtest
           10000
           (str "label-" (rand))
           "description")
  (invoice rpc-info-regtest
           10000
           (str "label-" (rand))
           "description"
           :expiry 3600
           :cltv 8)

  (let [getinfo-schema ;; params: 0 req / 0 opt
        {:required []
         :properties {}}
        newaddr-schema ;; params: 0 req / 1 opt
        {:required [],
         :properties {:addresstype {:type "string", :enum ["bech32" "p2tr" "all"]}}}
        decode-schema ;; params: 1 req / 0 opt
        {:required ["string"],
         :properties {:string {:type "string"}}}
        invoice-schema ;; params: 3 req / 6 opt
        {:required ["amount_msat" "label" "description"],
         :properties
         {:description {:type "string", :description ""},
          :amount_msat {:type "msat_or_any", :description ""},
          :expiry {:type "u64", :description ""},
          :deschashonly {:type "boolean", :description ""},
          :cltv {:type "u32", :description ""},
          :preimage {:type "hex", :description ""},
          :exposeprivatechannels
          {:oneOf
           [{:type "boolean", :description ""}
            {:type "array", :items {:type "short_channel_id"}}
            {:type "short_channel_id"}]},
          :fallbacks {:type "array", :description "", :items {:type "string"}},
          :label
          {:oneOf
           [{:type "string", :description ""}
            {:type "integer", :description ""}]}}}
        rpcmethods (list ["getinfo" getinfo-schema]
                         ["newaddr" newaddr-schema]
                         ["decode" decode-schema]
                         ["invoice" invoice-schema])]
    ;; (println (generate-rpcmethods-str rpcmethods))
    (write-rpcmethods-to-file "src/foo.clj" "v23.11" rpcmethods)
    )
  )
