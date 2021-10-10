(ns substrate.utils)

(defn uuid [] (java.util.UUID/randomUUID))

(defn eq [f a b] (= (f a) (f b)))

(defn neq [f a b] (not= (f a) (f b)))
