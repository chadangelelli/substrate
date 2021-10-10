##### Substrate

# --------------------------- Create

## API

```clojure
{:create! Document
 :grant {:user {"alice@test.com" #{:all}
                "bob@test.com" #{:update}}
         :team {#uuid "f5394715-8956-492b-b199-2138d7bd6dc2" #{:read}}}
 :set-state #{:hidden}
 :data {:name "My first doc"
        :ext "pdf"
        :size 123456
        :gcp_id #uuid "f5394715-8956-492b-b199-2138d7bd6dc2"}}
```

### Validation

```clojure
[:map {:closed true}
 [:create!                    keyword?]
 [:data                       map?] ; validated later against model
 [:grant     {:optional true} valid-perms-map]
 [:set-state {:optional true} [:set keyword?]]]))
```

# --------------------------- Update

## API

> _NOTE on `:data` key_:
> 
> - If a single map is provided it is a direct "overwrite"
> - If a vector or vectors is provided they are considered "field-level" updates

#### Update specific fields

```clojure
{:update! [:Document #uuid "f5394715-8956-492b-b199-2138d7bd6dc2"]
 :data [[:dissoc [:x]
        [:assoc [:y] 5]
        [:conj [:my :list] 23]]}
```

#### Update entire document

```clojure
{:update! [:Document #uuid "f5394715-8956-492b-b199-2138d7bd6dc2"]
 :data {:name "New name"
        :size 123456
        :ext "pdf"
        :gcp_id "1234"}} 
```

#### Security

```clojure
{:update! [:Document #uuid "f5394715-8956-492b-b199-2138d7bd6dc2"]

 :grant {:user {"alice@test.com" #{:all}
                #uuid "f1553947-1256-52e6-e111-76dc24b6582c" #{:update}}
         :team {#uuid "f5394715-8956-492b-b199-2138d7bd6dc2" #{:read}}
         
 :revoke {:user {"alice@test.com" #{:all}
                 #uuid "f1553947-1256-52e6-e111-76dc24b6582c" #{:update}
          :team {#uuid "f5394715-8956-492b-b199-2138d7bd6dc2" #{:read}}}
```

#### States

```clojure
{:update! [:Document #uuid "f5394715-8956-492b-b199-2138d7bd6dc2"]
 :set-state #{:hidden}
 :unset-state #{:archived}}
```

#### Kitchen sink

```clojure
{:update! [:Document #uuid "f5394715-8956-492b-b199-2138d7bd6dc2"]
 
 :data [[:dissoc [:x]
        [:assoc [:y] 5]
        [:conj [:my :list] 23]]
 
 :grant {:user {"alice@test.com" #{:all}
                #uuid "f1553947-1256-52e6-e111-76dc24b6582c" #{:update}}
         :team {#uuid "f5394715-8956-492b-b199-2138d7bd6dc2" #{:read}}
         
 :revoke {:user {"alice@test.com" #{:all}
                 #uuid "f1553947-1256-52e6-e111-76dc24b6582c" #{:update}
          :team {#uuid "f5394715-8956-492b-b199-2138d7bd6dc2" #{:read}}
 
 :set-state #{:hidden}
 
 :unset-state #{:archived}}
```

## Validation

```clojure
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
```

# --------------------------- Delete

# --------------------------- Find

```clojure
{:find [:User :Contact]
 :where [[:or
          [:= :user/email "chad@shorttrack.io"]
          [:* :user/email #"shorttrack\.io"]]]}
         
```