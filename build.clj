(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.tonyaldon/cln.rpc)
(def version "24.05")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" lib version))

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data
                [[:description "Core Lightning JSON-RPC client for Clojure."]
                 [:url "https://github.com/tonyaldon/cln.rpc"]
                 [:licenses
                  [:license
                   [:name "The MIT License"]
                   [:url "https://opensource.org/license/mit"]
                   [:distribution "repo"]]]
                 [:distributionManagement
                  [:repository
                   [:id "clojars"]
                   [:name "Clojars repository"]
                   [:url "https://clojars.org/repo"]]]]})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

;; env CLOJARS_USERNAME=username CLOJARS_PASSWORD=clojars-token clj -T:build deploy
(defn deploy [_]
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:class-dir class-dir :lib lib})}))
