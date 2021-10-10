(ns substrate.validation
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [substrate.error :refer (err)]))

(def email-regex #"(?i)[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")

(def uuid-regex #"\b[0-9a-f]{8}\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-\b[0-9a-f]{12}\b")

(defn valid-email?
  [value]
  (boolean (re-find email-regex (str value))))

(defn valid-uuid?
  [value]
  (boolean (or (uuid? value) (re-find uuid-regex (str value)))))

(defn valid-user-target?
  [x]
  (or (valid-email? x) (valid-uuid? x)))

(defn validate
  [schema value]
  (when-let [e (m/explain schema value)]
    (me/humanize e)))
