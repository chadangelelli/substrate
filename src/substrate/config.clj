(ns substrate.config
  "Config"
  {:author "Chad Angelelli"
   :added "0.1"}
  (:require [cprop.core :refer (load-config)]
            [cprop.source :as source]
            [environ.core :as environ]
            [substrate.error :refer (err)]))

(defonce config_ (atom nil))

(defn get-config
  "Returns current config if no k provided.
  Returns value at k if provided."
  {:added "0.1"}
  ([] @config_)
  ([x]
   (if (instance? java.util.regex.Pattern x)
     (into {} (filter (fn [[k v]] (re-find x (name k))) @config_))
     (get @config_ x))))

(defn set-config!
  "Resets config atom if given a map.
  Sets k to v in config atom if given [k v].
  Returns updated config."
  {:added "0.1"}
  ([m]   (reset! config_ m))
  ([k v] (swap! config_ assoc k v)))

(defn init-config!
  ([] (init-config! {}))
  ([args]
   (let [cnf (load-config
              :file "config.edn"
              :merge [environ/env
                      (source/from-system-props)
                      (source/from-env)
                      args])]
     (set-config! cnf))))
