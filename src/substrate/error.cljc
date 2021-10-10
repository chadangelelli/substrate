(ns substrate.error)

(defmacro err
  [m]
  (let [{:keys [line column]} (meta &form)]
    (assoc m
           :substrate/error? true
           :error/file *file*
           :error/line line
           :error/column column)))
