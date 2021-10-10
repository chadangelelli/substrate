(ns substrate.db
  "Database"
  {:author "Chad Angelelli"
   :added "0.1"}
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [xtdb.api :as xt]
   [malli.core :as m]
   [taoensso.timbre :as log]

   [substrate.config :as config]
   [substrate.validation :as v]
   [substrate.utils :as util]
   [substrate.model :as model]
   [substrate.error :refer (err)]))

(declare node_)

(def DataEvent
  {:event/id nil
   :event/start-time nil
   :event/end-time nil
   :event/logged? false
   :event/log-id nil
   :event/result nil})

(defn make-data-event
  ""
  {:added "0.1"}
  [& [m]]
  (merge DataEvent m {:event/id (util/uuid)
                      :event/start-time (System/nanoTime)}))

(defn make-data-event-response
  ""
  {:added "0.1"}
  [{:keys [:event/start-time] :as event} & [m]]
  (let [end-time      (System/nanoTime)
        total-time    (- end-time start-time)
        total-time-ms (/ (double total-time) 1000000.0)
        total-time-s  (/ (double total-time-ms) 1000.0)
        event         (merge
                       event
                       m
                       {:event/end-time                end-time
                        :event/total-time              total-time
                        :event/total-time-milliseconds total-time-ms
                        :event/total-time-seconds      total-time-s})]

    ;;TODO: implement logging

    event))

;;TODO: optimize get-user (maybe cache all users?)
;;TODO: clean up pull syntax (maybe just desired fields like [:user/email])
;;TODO: move into user lib
(defn get-user
  "Returns user for UUID or email. Optionally takes vector of
  fields to return. Default returns all fields.

  ```clojure
  (get-user #uuid \"c28c3279-7def-4ca4-ae41-bae7bd595e57\")

  (get-user \"c28c3279-7def-4ca4-ae41-bae7bd595e57\")

  (get-user \"chad.angelelli@gmail.com\")

  (get-user \"chad.angelelli@gmail.com\"
            '[:user/first_name :user/last_name :user/email])
  ```"
  {:added "0.1"}
  [x & [fields]]
  (let [k (if (v/valid-email? x) :user/email :xt/id)
        q {:find [(if (seq fields)
                    (conj '() fields '?user 'pull)
                    '(pull ?user [*]))]
           :where [['?user :ss/schema :User]
                   ['?user k x]]}]
    (-> (xt/q (xt/db node_) q) first first)))

;;TODO: decide on requiring security permissions to look up users
;;TODO: combine get-user & get-users
(defn get-users
  [user-ids & [fields]]
  ;;TODO: validate user-ids
  (->> (xt/q (xt/db node_)
             {:find [(if (seq fields)
                       (conj '() fields '?user 'pull)
                       '(pull ?user [*]))]
              :where ['[?user :ss/schema :User]
                      (apply list
                             'or
                             (map #(vector '?user
                                           (if (v/valid-email? %)
                                             :user/email
                                             :xt/id)
                                           %)
                                  user-ids))]})
       (mapv first)))

(defn user-exists?
  [uuid-or-email]
  (boolean (get-user uuid-or-email [:xt/id])))

(defn make-doc-perms
  [user-id doc]
  (assoc doc
         :ss/owner user-id
         :ss/perms-all #{user-id}))

(defn make-doc
  [{:keys [user-id schema doc-id data]}]
  (->> (assoc data
              :ss/schema schema
              :xt/id doc-id)
       (make-doc-perms user-id)))

(def valid-put-args
  (m/schema
   [:map {:closed true}
    ;;TODO: move common validation schemas into validation lib
    [:ss/user [:fn {:error/message "Invalid user target"} v/valid-user-target?]]
    [:ss/schema keyword?]
    [:ss/data map?]
    [:ss/event {:optional true} map?] ;TODO: validate DataEvent
    [:ss/debug? {:optional true} boolean?]]))

(defn invalidate-put-args
  "Returns nil on success or error event"
  {:added "0.1"}
  [event acting-user {schema-name :ss/schema :keys [:ss/data :ss/revoke] :as args}]

  (let [{:keys [form] :as schema} (model/get-schema schema-name)
        new?         (not (get-in args [:ss/data :xt/id]))
        args-error   (or (v/validate valid-put-args args)
                         (and new?
                              revoke
                              "Can't call revoke when creating new data"))
        data*        (dissoc data :xt/id)
        data-error   (v/validate form data*)
        user-schema? (= schema-name :User)]

    (cond
      ;; ..................... invalid args?
      args-error
      (err {:error/type :substrate/validation-error
            :error/fatal? false
            :error/message "Invalid put arguments"
            :error/data {:args args :validation-error args-error}})

      ;; ..................... invalid schema?
      (not schema)
      (err {:error/type :substrate/validation-error
            :error/fatal? false
            :error/message (str "Unknown schema '" schema-name "'")
            :error/data {:args args}})

      ;; ..................... invalid data?
      data-error
      (err {:error/type :substrate/validation-error
            :error/fatal? false
            :error/message (str "Invalid data")
            :error/data {:args args :validation-error data-error}})

      ;; ..................... duplicate user?
      (and new? user-schema? (user-exists? (:user/email data)))
      (err {:error/type :substrate/validation-error
            :error/fatal? false
            :error/message
            (str "Can't put User '" (:user/email data) "', already exists")
            :error/data {:args args}})

      ;; ..................... invalid acting user?
      (not acting-user)
      (err {:error/type :substrate/validation-error
            :error/fatal? false
            :error/message
            (str "Unknown acting user '" (:ss/user args) "'")
            :error/data {:args args}})

      ::else-validated
      nil)))

;;TODO: validate calling put w/ a nil event (arity 2)
(defn put
  ""
  {:added "0.1"}
  [{schema-name :ss/schema
    ss-user :ss/user
    :keys [ss/event ss/debug?]
    {_id :xt/id :as data} :ss/data
    :as args}]

  (let [event (or event (make-data-event))
        {user-id :xt/id :as user
         } (get-user ss-user [:xt/id :user/email])]

    (if-let [args-err (invalidate-put-args event user args)]
      (make-data-event-response event args-err)

      (let [new? (not _id)
            _id  (if new? (util/uuid) _id)
            doc  (if new?
                   (make-doc {:user-id user-id
                              :schema schema-name
                              :doc-id _id
                              :data data})
                   data)

            {:keys [query-error] :as tx
             ;;TODO: what does debug? do?
             } (if debug?
                 {:debug? true :doc doc}
                 (try
                   (xt/submit-tx node_ [[::xt/put doc]])
                   (catch Throwable t
                     {:query-error (.getMessage t)})))]

        (if query-error
          (make-data-event-response
           event
           (err {:error/type :substrate/query-error
                 :error/fatal? false
                 :error/message "Query error"
                 :error/data {:args args :query-error query-error}}))

          ;;TODO: look into keeping things non-blocking/async
          (let [_ (xt/await-tx node_ tx)
                r (xt/entity (xt/db node_) _id)]
            (make-data-event-response
             event
             {:event/result r})))))))

(defn create-initial-user!
  [user-data]
  (let [event        (make-data-event)
        users-exist? (-> (xt/q (xt/db node_)
                               '{:find [?u]
                                 :where [[?u :ss/schema :User]]
                                 :limit 1})
                         seq
                         boolean)
        form         (-> :User model/get-schema :form)
        invalid-user (v/validate form user-data)]

    (cond
      users-exist?
      (make-data-event-response
       event
       (err {:error/type :substrate/data-error
             :error/fatal? false
             :error/message (str "Can't call create-initial-user! "
                                 "when a user already exists")
             :error/data {:user-data user-data}}))

      invalid-user
      (make-data-event-response
       event
       (err {:error/type :substrate/validation-error
             :error/fatal? false
             :error/message "Invalid user provided to create-initial-user!"
             :error/data {:user-data user-data
                          :validation-error invalid-user}}))

      ::else-create-initial-user
      (let [_id (util/uuid)
            doc (make-doc {:user-id _id
                           :schema :User
                           :doc-id _id
                           :data user-data})

            {:keys [query-error] :as tx
             } (try
                 (xt/submit-tx node_ [[::xt/put doc]])
                 (catch Throwable t
                   {:query-error (.getMessage t)}))]

        (if query-error
          (make-data-event-response
           event
           (err {:error/type :substrate/query-error
                 :error/fatal? false
                 :error/message "Query error"
                 :error/data {:args user-data
                              :query-error query-error}}))

          ;;TODO: look into keeping things non-blocking/async
          (let [_ (xt/await-tx node_ tx)
                r (xt/entity (xt/db node_) _id)]
            (make-data-event-response
             event
             {:event/result r})))))))

(defn delete!
  ""
  {:added "0.1"}
  [{:keys [permanently-evict?]}]
  )

(defn q
  "Wraps xtdb.api/q with Substrate DataEvent."
  {:added "0.1"}
  [query & [event]]
  (let [event (or event (make-data-event))]
    (let [{:keys [query-error] :as r
           } (try
               (xt/q (xt/db node_) query)

               (catch java.lang.NullPointerException e
                 {:query-error (str "Null pointer. Check that the "
                                    "database is started and "
                                    "accepting connections.")})

               (catch Throwable t
                 {:query-error (.getMessage t)}))]

      (make-data-event-response
       event
       (if-not query-error
         (when (seq r)
           {:event/result r})
         (err {:error/type :substrate/query-error
               :error/fatal? false
               :error/message "Query error"
               :error/data {:query query :query-error query-error}}))))))

(defn start-db
  ""
  {:added "0.1"}
  []
  (defonce node_
    (xt/start-node
     {:ss/rocksdb {:xtdb/module 'xtdb.rocksdb/->kv-store
                   :db-dir (io/file "__db")}
      :xtdb/tx-log {:kv-store :ss/rocksdb}
      :xtdb/document-store {:kv-store :ss/rocksdb}
      :xtdb/index-store {:kv-store :ss/rocksdb}}))
  (log/info "[Substrate] [OK] Database started"))

(defn stop-db
  ""
  {:added "0.1"}
  []
  (try
    (.close node_)
    (catch Throwable t))
  (log/info "[Substrate] [OK] Database stopped"))

;;TODO: move into contrib
;;TODO: rewrite this mess!
;; (defn inspect
;;   [uuid]
;;   (let [uuid (if (uuid? uuid)
;;                uuid
;;                (java.util.UUID/fromString uuid))]
;;     (when-let [doc* (xt/entity (xt/db node_) uuid)]
;;       (let [doc-id  (:xt/id doc*)
;;             doc     (dissoc doc* :xt/id)
;;             ks      (keys doc)
;;             vs      (vals doc)
;;             data-ks (filter #(not (string/starts-with? (str %) ":ss")) ks)
;;             data    (select-keys doc data-ks)
;;             data-vs (vals data)
;;             usec-ks (filter #(string/starts-with? (str %) ":ss/up_") ks)
;;             usec    (select-keys doc usec-ks)
;;             usec-vs (vals usec)
;;             max-dk  (apply max (map #(count (str %)) data-ks))
;;             max-dv  (apply max (map #(count (str %)) data-vs))
;;             max-uk  (apply max (map #(count (str %)) usec-ks))
;;             max-uv  (apply max (map #(count (str %)) usec-vs))
;;             max-k   (max max-dk max-uk)
;;             max-v   (max max-dv max-uv)
;;             len     (+ max-k max-v)
;;             o     [(apply str (repeat 10 "\n"))
;;                    (apply str (repeat (+ len 10) "="))
;;                    (str ":xt/id => #uuid \"" doc-id "\"")
;;                    "\nData" (str "|" (apply str (repeat len "-")))]
;;             o     (into o
;;                         (map (fn [[k v]]
;;                                (let [kl (count (str k))
;;                                      vl (count (str v))]
;;                                  (str "| "
;;                                       k
;;                                       (apply str (repeat (- max-dk kl) " "))
;;                                       " | "
;;                                       v)))
;;                              data))
;;             o     (into o
;;                         ["\nPermissions" (str "|" (apply str (repeat len "-")))])
;;             o     (into o
;;                         (map (fn [[k v]]
;;                                (let [kl (count (str k))
;;                                      vl (count (str v))]
;;                                  (str "| "
;;                                       k
;;                                       (apply str (repeat (- max-k kl) " "))
;;                                       " | "
;;                                       v)))
;;                              usec))
;;             ]
;;         (print (string/join "\n" o))
;;         ))))


(defn inspect
  [uuid]
  (let [doc-id (if (uuid? uuid) uuid (java.util.UUID/fromString uuid))]
    (when-let [doc (xt/entity (xt/db node_) doc-id)]

      (let [str-count #(count (str %))

            extract
            (fn [doc pat]
              (let [x-ks  (filter #(re-find pat (str %)) (keys doc))
                    x     (into (sorted-map) (select-keys doc x-ks))
                    x-max (+ (apply max (map str-count x-ks))
                             (apply max (map str-count (vals x))))]
                [x-ks x x-max]))

            [xt-ks xt xt-max] (extract doc #":xt\/.*")
            doc (dissoc doc :xt/id)

            [perms-ks perms perms-max
             ] (extract doc #":ss\/(userperm|groupperm|owner)")
            doc (apply dissoc doc perms-ks)

            [sys-ks sys sys-max] (extract doc #":ss\/.*")
            doc (apply dissoc doc sys-ks)

            [data-ks data data-max] (extract doc #".*")
            doc (apply dissoc doc data-ks) ; NOTE: map should be empty now

            max-len (max xt-max sys-max perms-max data-max)

            print-table
            (fn [m]
              (let [max-k (apply max (map str-count (keys m)))
                    max-v (apply max (map str-count (vals m)))
                    rows (->> (map (fn [[k v]]
                                     (let [n (- max-k (str-count k))]
                                       [(str k (apply str (repeat n " ")))
                                        v]))
                                   m)
                              (map #(str "| " (string/join " | " %)))
                              (string/join "\n"))
                    header (->> ["| "
                                 (apply str (repeat (+ max-k max-v 3) "-"))]
                                (string/join ""))]

                (println)
                (println header)
                (println rows)))

            top-header (->> [(apply str (repeat 5 "\n"))
                             "|"
                             (apply str (repeat (+ max-len 8) "%"))]
                            (string/join ""))]

        (println top-header)
        (print-table xt)
        (print-table sys)
        (print-table data)
        (print-table perms)

        (println "\n\n")
        (clojure.pprint/pprint doc)))))

(comment
  (start-db)

  (inspect "b9de26ec-99e7-426e-9304-f02af8cf57e4")

  (let [test-users
        [{:ss/user "chad@shorttrack.io"
          :ss/schema :User
          :ss/data {:user/first_name "Chad"
                    :user/last_name "Angelelli"
                    :user/email "chad@shorttrack.io"}}
         {:ss/user "chris@shorttrack.io"
          :ss/schema :User
          :ss/data {:user/first_name "Chris"
                    :user/last_name "Angelelli"
                    :user/email "chris@shorttrack.io"}}
         {:ss/user "steve@shorttrack.io"
          :ss/schema :User
          :ss/data {:user/first_name "Steve"
                    :user/last_name "Angelelli"
                    :user/email "steve@shorttrack.io"}}]]
    (doseq [user test-users]
      (clojure.pprint/pprint (put user))))
  )
