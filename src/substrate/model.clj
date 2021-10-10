(ns substrate.model
  (:require
   [malli.core :as m]
   [substrate.validation :as v]
   [substrate.error :refer (err)]))

;; ...................................................... API
(def ^:private model_ (atom nil))

(defn get-model
  []
  @model_)

(defn get-schema
  [schema-name]
  (get @model_ schema-name))

(def valid-schema
  [:map {:closed true}
   [:name [:or keyword? string?]]
   [:description {:optional true} string?]
   [:validation [:enum :malli :schema :spec]] ;TODO: finish schema/spec
   [:form any?]
   [:hooks {:optional true}
    [:map {:closed true}
     [:pre {:optional true}
      [:map {:optional true}
       [:find {:optional true} [:fn fn?]]
       [:create {:optional true} [:fn fn?]]
       [:update {:optional true} [:fn fn?]]
       [:delete {:optional true} [:fn fn?]]]]
     [:post {:optional true}
      [:map {:optional true}
       [:find {:optional true} [:fn fn?]]
       [:create {:optional true} [:fn fn?]]
       [:update {:optional true} [:fn fn?]]
       [:delete {:optional true} [:fn fn?]]]]]]])

(defn set-schema!
  [{nm :name :as schema}]
  (if-let [e (v/validate valid-schema schema)]
    (err {:error/type :substrate/schema
          :error/message "Invalid schema"
          :error/data {:schema schema
                       :validation-error e}})
    (do
      (swap! model_ assoc (keyword nm) schema)
      nil)))

(defn unset-schema!
  [schema-name]
  (if-not (get-schema (keyword schema-name))
    (err {:error/type :substrate/model
          :error/message (str "Unknown schema '" schema-name "'")
          :error/data {:schema-name schema-name}})
    (do
      (swap! model_ dissoc schema-name)
      nil)))

;; ...................................................... BUILT-INS
(def
  ^{:added "0.1"
    :author "Chad Angelelli"
    :doc "Validates User. Map is open, additional fields are allowed."}
  User
  {:name :User
   :description "Default User schema"
   :validation :malli
   :form (m/schema
          [:map
           [:user/email [:fn v/valid-email?]]])})

(def
  ^{:added "0.1"
    :author "Chad Angelelli"
    :doc "Validates Group. Map is open, additional fields are allowed."}
  Group
  {:name :Group
   :description "Default Group schema"
   :validation :malli
   :form (m/schema
          [:map
           [:group/name string?]
           [:group/description {:optional true} string?]
           [:group/members [:vector int?]]])})

(do ; Set User/Group models automatically for security permissions
  (set-schema! User)
  (set-schema! Group)
  nil)

(comment
  (let [test-schemas
        [{:name :Transaction
          :description "A real estate transaction"
          :validation :malli
          :form (m/schema
                 [:map
                  [:address/street string?]
                  [:address/city string?]
                  [:address/state string?]
                  [:address/zip string?]])}
         {:name :Document
          :description "A document"
          :validation :malli
          :form (m/schema
                 [:map
                  [:name string?]])}]]
    (doseq [schema test-schemas]
      (set-schema! schema)))
  )
