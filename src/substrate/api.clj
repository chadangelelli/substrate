(ns substrate.api
  (:refer-clojure :exclude [find])
  (:require
   [clojure.set :as set]
   [malli.core :as m]

   [substrate.config :as config]
   [substrate.validation :as v :refer (validate)]
   [substrate.model :as model]
   [substrate.db :as db]
   [substrate.utils :as util]
   [substrate.error :refer (err)]))

;; __________________________________________________________________ GENERIC
(def actions #{:create! :update! :delete! :set-state :find})

(def valid-user-target
  (m/schema [:fn {:error/message "Invalid user target"} v/valid-user-target?]))

(def valid-group-target
  (m/schema [:fn {:error/message "Invalid group target"} v/valid-uuid?]))

(def valid-state-set
  (m/schema [:set keyword?]))

(def valid-write-target
  (m/schema
   [:catn [:schema keyword?] [:gem-id uuid?]]))

(def valid-data-path
  [:cat keyword? [:+ [:alt keyword? integer?]]])

;; __________________________________________________________________ PERMS
(def permissions #{:read :update :state :owner :all})

(defn make-valid-perms
  ""
  {:added "0.1"}
  [msg]
  [:set
   [:fn
    {:error/message msg}
    (fn make-valid-perms* [v] (boolean (some permissions [v])))]])

(def valid-user-perms
  (m/schema (make-valid-perms "Invalid user permissions")))

(def valid-group-perms
  (m/schema (make-valid-perms "Invalid group permissions")))

(def valid-perms-map
  (m/schema
   [:and
    [:map {:closed true}
     [:user {:optional true}
      [:map-of valid-user-target valid-user-perms]]
     [:group {:optional true}
      [:map-of valid-group-target valid-group-perms]]]
    [:fn
     {:error/message "Permissions map cannot be empty"}
     #(> (count (keys %)) 0)]]))

(defn make-user-id-lookup
  [users-map]
  (reduce (fn [m {:keys [:user/email :xt/id]}]
            (assoc m email id))
          {}
          users-map))

(defn make-user-perms
  [perms user-perms]
  (if-not (seq user-perms)
    perms
    (let [user-ids (keys user-perms)
          users    (db/get-users user-ids [:xt/id :user/email])]

      (if (util/neq count user-ids users)
        [nil (err {:error/type :substrate/data-error
                   :error/fatal? false
                   :error/message "Could not find all users for grant"
                   :error/data
                   {:user-perms user-perms
                    :unknown-users
                    (set/difference (set user-ids)
                                    ;;TODO: handle emails + UUIDs
                                    (set (map :user/email users)))}})]

        (let [lu    (make-user-id-lookup users)
              perms (reduce
                     (fn [o [ux ps]]
                       (reduce
                        #(let [k (keyword "ss" (str "perms-" (name %2)))]
                           (assoc %1 k (conj (or (get %1 k) #{}) (get lu ux))))
                        o
                        ps))
                     perms
                     user-perms)]
          [perms nil])))))

(defn make-group-perms
  [perms group-perms]
  ;;TODO: finish
  [perms nil])

(defn make-perms
  [{{user-perms :user group-perms :group} :grant :as q}]
  (if (or user-perms group-perms)
    (let [[perms err] (make-user-perms {} user-perms)]
      (if err
        [nil err]
        (let [[perms err] (make-group-perms perms group-perms)]
          (if err
            [nil err]
            [perms nil]))))
    [nil nil]))

;; __________________________________________________________________ CREATE
(def valid-create-query
  (m/schema
   [:map {:closed true}
    [:create!                    keyword?]
    [:data                       map?] ; validated later against model
    [:grant     {:optional true} valid-perms-map]
    [:set-state {:optional true} valid-state-set]]))

(def valid-create-args
  (m/schema
   [:map {:closed true}
    [:ss/user valid-user-target]
    [:ss/q    valid-create-query]]))

(defn create!
  ""
  {:added "0.1"}
  [{:keys [ss/user ss/q] {:keys [data]} :ss/q :as args}]
  (let [event (db/make-data-event)]

    (if-let [args-error (validate valid-create-args args)]
      (db/make-data-event-response
       event
       (err {:error/type :substrate/validation-error
             :error/fatal? false
             :error/message "Invalid create! arguments"
             :error/data {:args args :validation-error args-error}}))

      (let [[perms perms-err] (make-perms q)]
        (if perms-err
          (db/make-data-event-response event perms-err)
          (let [data* (merge data perms)]
            (db/put {:ss/event  event
                     :ss/user   user
                     :ss/schema (:create! q)
                     :ss/data   data*})))))))

(let [user {:user/first_name "Chris"
            :user/last_name "Hacker"
            :user/email "chris@shorttrack.io"}]
  (println "\n\n\n\n")
  (clojure.pprint/pprint
   (try
     (create! {:ss/user "chad@shorttrack.io"
              :ss/q {:create! :User :data user}})
     (catch Throwable t
       (println (.getMessage t))))))


(let [user {:user/first_name "Steve"
            :user/last_name "Hargraves"
            :user/email "steve@shorttrack.io"}]
  (println "\n\n\n\n")
  (clojure.pprint/pprint
   (try
     (create! {:ss/user "chad@shorttrack.io"
              :ss/q {:create! :User
                     :data user
                     :grant {:user {"chris@shorttrack.io" #{:update}
                                    "chad@shorttrack.io" #{:all :update :owner}}}}})
     (catch Throwable t
       (println (.getMessage t))))))




;; __________________________________________________________________ UPDATE
(def valid-3-arity-update-actions
  [:enum :assoc :conj :concat])

(def valid-2-arity-update-actions
  [:enum :dissoc :disj])

(def valid-update-data
  (m/schema
   [:or
    map?
    [:vector
     [:or
      [:tuple valid-3-arity-update-actions valid-data-path any?]
      [:tuple valid-2-arity-update-actions valid-data-path]]]]))

(def valid-update-query
  (m/schema
   [:and
    [:map {:closed true}
     [:update!                      valid-write-target]
     [:data        {:optional true} valid-update-data]
     [:grant       {:optional true} valid-perms-map]
     [:revoke      {:optional true} valid-perms-map]
     [:set-state   {:optional true} valid-state-set]
     [:unset-state {:optional true} valid-state-set]]
    [:fn
     {:error/message "Must provide at least one action for update query"}
     #(> (count (keys %)) 1)]]))

(def valid-update-args
  (m/schema
   [:map {:closed true}
    [:ss/user valid-user-target]
    [:ss/q    valid-update-query]]))

(defn update!
  ""
  {:added "0.1"}
  [{:keys [ss/user ss/q] {:keys [data]} :ss/q :as args}]
  (let [event (db/make-data-event)]

    (if-let [args-error (validate valid-update-args args)]
      (db/make-data-event-response
       event
       (err {:error/type :substrate/validation-error
             :error/fatal? false
             :error/message "Invalid update! arguments"
             :error/data {:args args :validation-error args-error}}))

      )))

;; __________________________________________________________________ DELETE
(defn delete!
  ""
  {:added "0.1"}
  []
  )

;; __________________________________________________________________ FIND
(defn find
  ""
  {:added "0.1"}
  []
  )

(comment
  (let [test-create-q {:create! :Transaction
                       :grant {:user {"chris@shorttrack.io" #{:all}
                                      "steve@shorttrack.io" #{:update}}
                               :group {52 #{:read}}}
                       :set-state #{:hidden}
                       :data {:address/street (str (rand-int 500) " Test St")
                              :address/city "Chicago"
                              :address/state "IL"
                              :address/zip "60612"}}]

    ;; basic validation
    (do (println "\n\n")
        (println (validate valid-create-query test-create)))

    ;; create
    (do (println "\n")
        (clojure.pprint/pprint
         (create! {:ss/user "chad@shorttrack.io"
                   :ss/q test-create-q}))))

  (let [x {:update! [:Document #uuid "b686b106-3ab9-4362-9a13-c80357b24a99"],
           ;; :data [[:dissoc [:x] [:assoc [:y] 5] [:conj [:my :list] 23]]],
           :grant
           {:user
            {"alice@test.com" #{:all},
             #uuid "516138bc-b47b-47a4-b42c-a3a66d7b2688" #{:update}},
            :group {#uuid "d095d938-2038-4b1c-af76-083d8b0c2413" #{:read}}},
           :revoke
           {:user
            {"alice@test.com" #{:all},
             #uuid "516138bc-b47b-47a4-b42c-a3a66d7b2688" #{:update}},
            :group {#uuid "d095d938-2038-4b1c-af76-083d8b0c2413" #{:read}}},
           :set-state #{:hidden},
           :unset-state #{:archived}}]
    (println "\n\n\n")
    (clojure.pprint/pprint (validate valid-update-query x)))


  (clojure.pprint/pprint
   (update!
    {:ss/user "chad@shorttrack.io"
     :ss/q {:update! [:Transaction #uuid "4a2a8902-a252-4e48-9d92-3725092fad73"]
            :grant {:user {"chris@shorttrack.io" #{:all}}}}}))


  )
