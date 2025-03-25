;; SPDX-FileCopyrightText: 2024, 2025 SURF B.V.
;; SPDX-License-Identifier: EPL-2.0 WITH Classpath-exception-2.0
;; SPDX-FileContributor: Joost Diepenmaat
;; SPDX-FileContributor: Michiel de Mare
;; SPDX-FileContributor: Remco van 't Veer

(ns nl.jomco.apie.report
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [hiccup.page]
            [hiccup.util]
            [nl.jomco.apie.report.json :as json]
            [nl.jomco.openapi.v3.example :as example])
  (:import java.net.URLEncoder))

(def max-issues-per-schema-path 10)
(def max-issues 3)
(def max-value-depth 1)
(def max-value-length 120)

(defn t-count [{:keys [zero one other] :as templ}
               {:keys [count] :as _args}]
  (if (and count (map? templ))
    (cond
      (and (zero? count) zero) zero
      (and (= 1 count) one) one
      :else other)
    templ))

(defn t [templ args]
  (let [templ (t-count templ args)]
    (assert (string? templ))
    (reduce (fn [r [k v]]
              (string/replace r (str "%{" (name k) "}") (str v)))
            templ
            args)))

(defn- pretty-json [v & opts]
  [:pre.json (apply json/to-s v opts)])

(defn- with-issues [interactions]
  (filter :issues interactions))

(def css-resources ["style.css" "extra.css"])

(defn- interactions-result [interactions]
  [:section.result
   [:h3 "Results"]
   [:dl
    [:div
     [:dt "Number of request with issues"]
     [:dd
      [:strong (-> interactions
                   (with-issues)
                   (count))]]]

    [:div
     [:dt "Number of paths with issues"]
     [:dd
      [:strong (->> interactions
                    (with-issues)
                    (map :operation-path)
                    (set)
                    (count))]]]


    [:div
     [:dt "Total number of issues"]
     [:dd
      [:strong (->> interactions
                    (mapcat :issues)
                    (count))]]]

    [:div
     [:dt "Number of issue types (by schema path)"]
     [:dd
      [:strong (->> interactions
                    (mapcat :issues)
                    (map :canonical-schema-path)
                    (set)
                    (count))]]]]])

(defn- duration [msecs]
  (let [secs (quot msecs 1000)
        terms (->> (cond-> []
                     (> secs (* 24 60 60))
                     (conj (t {:zero  ""
                               :one   "1 day"
                               :other "%{count} days"}
                              {:count (quot secs (* 24 60 60))}))

                     (> secs (* 60 60))
                     (conj (t {:zero  ""
                               :one   "1 hour"
                               :other "%{count} hours"}
                              {:count (mod (quot secs (* 60 60)) 24)}))

                     (> secs 60)
                     (conj (t {:zero  ""
                               :one   "1 minute"
                               :other "%{count} minutes"}
                              {:count (mod (quot secs 60) 60)}))

                     :else
                     (conj (t {:zero  ""
                               :one   "1 second"
                               :other "%{count} seconds"}
                              {:count (mod secs 60)})))
                   (filter (complement (partial = ""))))]
    (cond
      (= 1 (count terms))
      (first terms)

      (seq terms)
      (str (string/join ", " (drop-last terms))
           " and "
           (last terms))

      :else
      "less than a second")))

(defn- interactions-runtime [base-url interactions]
  (let [responses (->> interactions
                       (map :response))
        start-at     (->> responses
                          (map :start-at)
                          (sort)
                          (first))
        finish-at    (->> responses
                          (map :finish-at)
                          (sort)
                          (last))]
    [:section.runtime
     [:h3 "Run"]
     [:dl
      [:div
       [:dt "Location"]
       [:dd [:a {:href base-url} base-url]]]

      [:div
       [:dt "Started at"]
       [:dd start-at]]

      [:div
       [:dt "Finished at"]
       [:dd finish-at]]

      [:div
       [:dt "Duration"]
       [:dd (duration (- (.getTime finish-at)
                         (.getTime start-at)))]]

      [:div
       [:dt "Number of requests"]
       [:dd (count interactions)]]]]))

(defn- interaction-summary [{{:keys [method uri query-string query-params]} :request}]
  [:span.interaction-summary
   [:code.method (string/upper-case (name method))]
   " "
   [:code.url uri (cond
                    query-string
                    (str "?" query-string)

                    query-params
                    (str "?"
                         (->> query-params
                              (map #(str (URLEncoder/encode (key %)) "=" (URLEncoder/encode (val %))))
                              (string/join "&"))))]])

(defn- value-type [v]
  (cond
    (sequential? v) "array"
    (boolean? v)    "boolean"
    (integer? v)    "integer"
    (map? v)        "object"
    (nil? v)        "null"
    (number? v)     "number"
    (string? v)     "string"))

(declare issue-snippets-list)

(def json-id-re #"[a-zA-Z_$][0-9a-zA-Z_$]*")

(defn- path->hiccup [coll]
  (into [:span.path]
        (reduce (fn [m k]
                  (let [d (if (empty? m) nil ".")
                        k (if (keyword? k) (name k) k)]
                    (conj m
                          (if (and (string? k) (re-matches json-id-re k))
                            [:span d [:code k]]
                            [:span "[" [:code (json/to-s k) "]"]]))))
                []
                coll)))

(defn- path-summary [path]
  (->> path
       (path->hiccup)))

(defn- json-schema-title
  [{:strs [title $ref] :as schema}]
  (cond
    title
    [:q.title title]

    $ref
    [:code.ref $ref]

    :else
    [:code.expected (json/to-s schema)]))

(defn- list-summary [coll]
  [:span.list
   "["
   (->> coll
        (map json-schema-title)
        (interpose ", "))
   "]"])

(defn- issue-example
  [openapi {:keys [schema-keyword canonical-schema-path]}]
  (when schema-keyword
    ;; schema-keyword issues have a full json-schema as the parent of
    ;; the schema keyword
    (example/example openapi (subvec canonical-schema-path 0 (dec (count canonical-schema-path))))))



(defmulti json-schema-issue-summary
  (fn [_ issue]
    (:schema-keyword issue)))

;; TODO "additionalProperties"

;; TODO "allOf"

(defmethod json-schema-issue-summary "anyOf"
  [_ {:keys [schema schema-keyword path]}]
  [:span
   (path-summary path) " "
   "expected " [:strong "any of"]
   " (validated against none): "
   (list-summary (get schema schema-keyword))])

;; TODO "const"

(defmethod json-schema-issue-summary "contains"
  [_ {:keys [schema schema-keyword instance path]}]
  [:span
   (path-summary path) " "
   (t {:one "expected list of one item to "
       :other "expected list of %{count} items to "}
      {:count (count instance)})
   [:strong "contain"]
   " at least one valid "
   (json-schema-title (get schema schema-keyword))])

;; TODO "dependentRequired"

;; TODO "discriminator"

(defmethod json-schema-issue-summary "enum"
  [_ {:keys [schema schema-keyword path]}]
  [:span
   (path-summary path) " "
   "expected " [:strong "enum"] " value to be one of: "
   (list-summary (get schema schema-keyword))])

(defmethod json-schema-issue-summary "exclusiveMaximum"
  [_ {:keys [schema path]}]
  [:span
   (path-summary path) " "
   "expected number to be smaller than " (schema "exclusiveMaximum")])

;; TODO "if then else"

(defmethod json-schema-issue-summary "exclusiveMinimum"
  [_ {:keys [schema path]}]
  [:span
   (path-summary path) " "
   "expected number to be larger than " (schema "exclusiveMinimum")])

;; TODO "items"

(defmethod json-schema-issue-summary "format"
  [_ {:keys [schema path]}]
  [:span
   (path-summary path) " "
   "expected string to be of format: " [:code (schema "format")]])

(defmethod json-schema-issue-summary "maxItems"
  [_ {:keys [schema path hints]}]
  [:span
   (path-summary path) " "
   (t {:one   "expected collection to contain no more than one item"
       :other "expected collection to contain no more than %{count} items"}
      {:count (schema "maxItems")})
   (when-let [n (:count hints)] (str " (got " n ")"))])

(defmethod json-schema-issue-summary "maxLength"
  [_ {:keys [schema path hints]}]
  [:span
   (path-summary path) " "
   (t {:one   "expected string to contain no more than one character"
       :other "expected string to contain no more than %{count} characters"}
      {:count (schema "maxLength")})
   (when-let [n (:count hints)] (str " (got " n ")"))])

(defmethod json-schema-issue-summary "maxProperties"
  [_ {:keys [schema path hints]}]
  [:span
   (path-summary path) " "
   (t {:one   "expected object to contain no more than one property"
       :other "expected object to contain no more than %{count} properties"}
      {:count (schema "maxProperties")})
   (when-let [n (:count hints)] (str " (got " n ")"))])

(defmethod json-schema-issue-summary "maximum"
  [_ {:keys [schema path]}]
  [:span
   (path-summary path) " "
   "expected number to be " (schema "maximum") " or smaller"])

(defmethod json-schema-issue-summary "minItems"
  [_ {:keys [schema path hints]}]
  [:span
   (path-summary path) " "
   (t {:one   "expected collection to contain at least one item"
       :other "expected collection to contain at least %{count} items"}
      {:count (schema "minItems")})
   (when-let [n (:count hints)] (str " (got " n ")"))])


(defmethod json-schema-issue-summary "minLength"
  [_ {:keys [schema path hints]}]
  [:span
   (path-summary path) " "
   (t {:one   "expected string to contain at least one character"
       :other "expected string to contain at least %{count} characters"}
      {:count (schema "minLength")})
   (when-let [n (:count hints)] (str " (got " n ")"))])

(defmethod json-schema-issue-summary "minProperties"
  [_ {:keys [schema path hints]}]
  [:span
   (path-summary path) " "
   (t {:one   "expected object to contain at least one property"
       :other "expected object to contain at least %{count} properties"}
      {:count (schema "minProperties")})
   (when-let [n (:count hints)] (str " (got " n ")"))])

(defmethod json-schema-issue-summary "minimum"
  [_ {:keys [schema path]}]
  [:span
   (path-summary path) " "
   "expected number to be " (schema "minimum") " or larger"])

(defmethod json-schema-issue-summary "multipleOf"
  [_ {:keys [schema path]}]
  [:span
   (path-summary path) " "
   "expected number to be a multiple of " (schema "multipleOf")])

;; TODO "not"

(defmethod json-schema-issue-summary "oneOf"
  [_ {:keys [schema schema-keyword hints path]}]
  [:span
   (path-summary path) " "
   "expected exactly " [:strong "one of"]
   " (validated against " (:ok-count hints) "): "
   (list-summary (get schema schema-keyword))
   (when (pos? (:ok-count hints))
     [:span " (but instance validates to " [:strong "all"] " the schemas!)"])])

(defmethod json-schema-issue-summary "pattern"
  [_ {:keys [schema path]}]
  [:span
   (path-summary path) " "
   "expected string to be a match regular expression: " [:code (schema "pattern")]])

;; TODO "patternProperties"

;; TODO "prefixItems"

;; TODO "propertyNames"

;; TODO "properties"

(defmethod json-schema-issue-summary "required"
  [_ {:keys [hints path]}]
  [:span
   (path-summary path) " "
   "missing " [:strong "required"] " field(s): "
   (list-summary (:missing hints))])

(defmethod json-schema-issue-summary "type"
  [_ {:keys [schema schema-keyword instance path]}]
  [:span
   (path-summary path) " "
   "expected " [:strong "type"] " "
   [:code.expected (get schema schema-keyword)]
   ", got "
   [:code.got (value-type instance)]])

;; TODO "uniqueItems"

(defmethod json-schema-issue-summary :default
  [_ {:keys [schema-keyword path]}]
  [:span
   (path-summary path) " "
   "JSON Schema Issue: " [:code schema-keyword]])



(defmulti issue-details
  "Details for validation issue, dispatches on json schema-keyword"
  (fn [_ {:keys [schema-keyword]}]
    schema-keyword))

(defmethod issue-details "anyOf"
  [openapi {:keys [schema schema-keyword sub-issues]}]
  (->> sub-issues
       (keep-indexed
        (fn [i issues]
          (when (seq issues)
            [:li
             (t {:one   "Invalid %{title}, one issue:"
                 :other "Invalid %{title}, %{count} issues:"}
                {:count (count issues)
                 :title (-> schema
                            (get-in [schema-keyword i])
                            (json-schema-title))})
             (issue-snippets-list openapi issues)])))
       (into [:ul])))

(defmethod issue-details "oneOf"
  [openapi {:keys [schema schema-keyword hints sub-issues]}]
  (if (zero? (:ok-count hints))
    (->> sub-issues
         (keep-indexed
          (fn [i issues]
            (when (seq issues)
              [:li
               (t {:one   "Invalid %{title}, one issue:"
                   :other "Invalid %{title}, %{count} issues:"}
                  {:count (count issues)
                   :title (-> schema
                              (get-in [schema-keyword i])
                              (json-schema-title))})
               (issue-snippets-list openapi issues)])))
         (into [:ul]))
    [:p "Maybe a fault in the specification?"]))

(defmethod issue-details "contains"
  [openapi {:keys [instance sub-issues schema schema-keyword]}]
  (if (seq instance)
    (let [title (json-schema-title (get schema schema-keyword))]
      (->> sub-issues
           (map-indexed
            (fn [i issues]
              (when (seq issues)
                [:div
                 [:dt (t {:one   "Item %{i} is not a valid %{title}; one issue"
                          :other "Item %{i} is not a valid %{title}; %{count} issues"}
                         {:i     i
                          :count (count issues)
                          :title title})]
                 [:dd (issue-snippets-list openapi issues)]])))
           (into [:dl])))
    [:dl [:dt "Collection is empty!"]]))

;; this also works for non-json-schema issue types
(defmethod issue-details :default
  [openapi {:keys [instance path schema-path] :as issue}]
  [:dl
   (if-let [example (issue-example openapi issue)]
     [:div.side-by-side
      [:div
       [:dt "Value"]
       [:dd (pretty-json instance
                         :max-depth max-value-depth
                         :max-length max-value-length)]]
      [:div
       [:dt "Example from schema"]
       [:dd (pretty-json example)]]]
     [:div
      [:dt "Value"]
      [:dd (pretty-json instance
                        :max-depth max-value-depth
                        :max-length max-value-length)]])
   (for [[label value]
         {"Path in body"        (path->hiccup path)
          "Full schema path"    (path->hiccup schema-path)}]
     (when value
       [:div
        [:dt label]
        [:dd value]]))
   [:div
    [:dt "Issue data"]
    [:dd (-> issue
             (dissoc :canonical-schema-path :instance :interaction :path :schema-path :schema-keyword)
             (pretty-json))]]])



(defmulti issue-summary
  "One sentance summary of issue"
  (fn [_ issue]
    (:issue issue)))

(defmethod issue-summary "schema-validation-error"
  [openapi issue]
  (json-schema-issue-summary openapi issue))

(defmethod issue-summary "status-error"
  [_ {:keys [hints instance path]}]
  [:span
   (path-summary path) " expected one of: "
   (list-summary (:ranges hints))
   ", got " [:code instance]])

(defmethod issue-summary :default
  [_ {:keys [issue]}]
  [:span
   "Issue: " [:code.issue-type issue]])



(defn issue-snippet
  "Display issue with summary and details"
  [openapi issue _i]
  [:details.issue
   [:summary.issue (issue-summary openapi issue)]
   (issue-details openapi issue)])

(defn- interaction-snippet
  "Display interaction with method, path, summary and details"
  [openapi {:keys [interaction] :as issue} _i]
  [:details.interaction
   [:summary
    [:div.headline (interaction-summary interaction)]
    [:div.summary (issue-summary openapi issue)]]
   (issue-details openapi issue)])

(defn- issue-snippets-list
  [openapi issues]
  [:ul.issues
   (for [[issue i] (map vector
                        (take max-issues issues)
                        (iterate inc 0))]
     [:li (issue-snippet openapi issue i)])
   (when (> (count issues) max-issues)
     [:li.and-more
      "and "
      (- (count issues) max-issues)
      " more.."])])

(defn- interaction-snippet-list
  [openapi issues]
  [:ul.interactions
   (for [[issue i]
         (map vector
              (take max-issues issues)
              (iterate inc 0))]
     [:li (interaction-snippet openapi issue i)])
   (when (> (count issues) max-issues)
     [:li.and-more
      "and "
      (- (count issues) max-issues)
      " more.."])])

(defn- per-path-section [openapi interactions]
  [:section.per-path
   [:h2 "Results per request path"]
   [:p "(sorted by number of issues)"]

   (for [[[_ path] interactions]
         (->> interactions
              (group-by :operation-path)
              (sort-by (fn [[path interactions]]
                         [(* -1 (-> interactions
                                    (with-issues)
                                    (count)))
                          path])))]
     [:section.interaction-path
      [:h3 path]
      (let [n             (count interactions)
            n-with-issues (->> interactions (filter :issues) (count))]
        [:div
         [:div.summary
          (cond
            (zero? n-with-issues)
            "No issues found."

            (and (pos? n)
                 (= n n-with-issues))
            (t {:one   "Only request has issues."
                :other "All %{count} requests have issues."}
               {:count n})

            :else
            (t {:one   "%{n-with-issues} of one request have issues."
                :other "%{n-with-issues} of %{count} requests have issues."}
               {:n-with-issues n-with-issues
                :count         n}))]

         (when (pos? n-with-issues)
           (let [issues-by-schema-path (->> interactions
                                            (mapcat (fn [interaction]
                                                      (map #(assoc % :interaction interaction)
                                                           (:issues interaction))))
                                            (group-by :canonical-schema-path)
                                            (sort-by (fn [[path issues]]
                                                       [(* -1 (count issues)) path])))
                 n-issue-types         (count issues-by-schema-path)]
             [:div.summary
              [:p
               (t {:one   "1 validation issue (by schema path):"
                   :other "%{count} different validation issues (by schema path):"}
                  {:count n-issue-types})]
              [:ol.by-schema-path
               (for [[[schema-path issues] i]
                     (map vector
                          (take max-issues-per-schema-path
                                issues-by-schema-path)
                          (iterate inc 0))]
                 [:li
                  [:details.schema-path (when (= 0 i) {:open true})
                   [:summary
                    [:div.leading [:span.count (count issues)]]
                    [:div.headline
                     [:div.schema-path (string/join "/" schema-path)]
                     [:div.summary
                      (t {:one   "one issue in "
                          :other "%{count} issues in "}
                         {:count (count issues)})
                      (t {:one   "one request"
                          :other "%{count} requests"}
                         {:count (count (filter (fn [{:keys [issues]}]
                                                  (some #(= schema-path (:canonical-schema-path %)) issues))
                                                interactions))})]]]
                   (interaction-snippet-list openapi issues)]])

               (when (> (count issues-by-schema-path) max-issues-per-schema-path)
                 [:li.and-more
                  "and "
                  (- (count issues-by-schema-path)
                     max-issues-per-schema-path)
                  " more.."])]]))])])])

(defn- raw-css [css]
  (hiccup.util/raw-string "/*<![CDATA[*/\n" css "/*]]>*/"))

(defn report-title
  [base-url]
  (str "Validation report for " base-url))

(defn report
  [openapi interactions base-url]
  (hiccup.page/html5
   [:html
    [:head
     [:title (report-title base-url)]
     [:meta {:charset "UTF-8"}]
     [:style (->> css-resources
                  (map #(some-> % io/resource slurp))
                  (string/join)
                  (raw-css))]]
    [:body
     [:header
      [:h1 (report-title base-url)]]

     [:main
      [:section.general
       (interactions-result interactions)
       (interactions-runtime base-url interactions)]
      (per-path-section openapi interactions)]

     [:footer
      "This report was generated at " (java.util.Date.)]]]))
