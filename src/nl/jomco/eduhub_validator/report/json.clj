(ns nl.jomco.eduhub-validator.report.json
  (:require [clojure.data.json :as json]
            [clojure.string :as string]))

(defn to-s
  "Transform `val` to a JSON string.
  Use the `max-depth` and `max-length` options to truncate output
  using `ellipsis` (which defaults to `…`)."
  [val & {:keys [depth ellipsis pad max-depth max-length]
          :or   {depth 0, pad "  ", ellipsis "…"}
          :as   opts}]
  (let [padding (-> depth (repeat pad) (string/join))]
    (str
     padding
     (if (and max-depth (> depth max-depth))
       ellipsis
       (cond
         (map? val)
         (if (empty? val)
           "{}"
           (str "{\n"
                (string/join
                 ",\n"
                 (for [[k v] val]
                   (str
                    padding pad
                    (to-s k (assoc opts :depth 0))
                    ": "
                    (string/trim ;; remove indent on first element
                     (to-s v (update opts :depth (fnil inc 0)))))))
                "\n"
                padding
                "}"))

         (coll? val)
         (if (empty? val)
           "[]"
           (str "[\n"
                (string/join
                 ",\n"
                 (for [v val]
                   (to-s v (update opts :depth (fnil inc 0)))))
                "\n"
                padding
                "]"))

         ;; for anything else fall back to clojure.data.json
         :else
         (let [v (json/write-str val :escape-slash false, :escape-unicode false)]
           (if (and max-length (> (count v) max-length))
             (str (subs v 0 max-length) ellipsis)
             v)))))))
