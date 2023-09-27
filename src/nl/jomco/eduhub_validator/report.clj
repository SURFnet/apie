(ns nl.jomco.eduhub-validator.report
  (:require [clojure.data.json :as data.json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [hiccup.page]
            [hiccup.util]
            [nl.jomco.eduhub-validator.report.json :as json]
            [nl.jomco.openapi.v3.example :as example]))

(def cli-options
  [["-o" "--openapi OPENAPI-PATH" "OpenAPI specification"
    :missing "openapi path is required"]
   ["-w" "--write-to REPORT-PATH" "Path of output file"]])

(def max-issues-per-schema-path 10)
(def max-issues 3)
(def max-value-depth 1)
(def max-value-length 120)

(defn- pretty-json [v & opts]
  [:pre.json (apply json/to-s v opts)])

(defn- with-issues [interactions]
  (filter :issues interactions))

(defn- score-percent [interactions]
  (* 100.0 (/ (count (with-issues interactions))
              (count interactions))))

(defn- score-summary [interactions]
  (let [score (score-percent interactions)]
    [:div.score
      (if (zero? score)
        (str "🙂 no issues found!")
        (format "😢 %.1f%% (%d) observations have issues."
                score
                (count (with-issues interactions))))]))


(def report-title "SURFeduhub validation report")
(def css-resource "style.css")

(defn- interactions-summary [interactions]
  (let [server-names (->> interactions
                          (map :request)
                          (map :server-name)
                          set)
        start-at     (->> interactions
                          (map :start-at)
                          (sort)
                          (first))
        finish-at    (->> interactions
                          (map :finish-at)
                          (sort)
                          (last))]
    [:section.summary
     [:h3 "Summary"]
     [:dl
      [:div
       [:dt "Server" (if (> (count server-names) 1) "s" "")]
       [:dd (interpose ", "
                       (map #(vector :strong %) (sort server-names)))]]
      [:div
       [:dt "Run time"]
       [:dd "From "[:strong start-at] " till " [:strong finish-at]]]]]))

(defn- kpis-section [interactions]
  [:section.kpis
   [:h3 "KPIs"]
   (let [n             (count interactions)
         n-with-issues (count (with-issues interactions))]
     [:dl
      [:div.observations
       [:dt "Observations"]
       [:dd n]]
      [:div.faultless
       [:dt "Faultless observations"]
       [:dd (str "✅ " (- n n-with-issues) " observations have no issues!")]]
      [:div.score
       [:dt "Validation score"]
       [:dd (score-summary interactions)]]])])

(defn- interaction-summary [{{:keys [method url]} :request}]
  [:span.interaction-summary
   [:code.method (string/upper-case (name method))]
   " "
   [:code.url url]])

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

(defmulti json-schema-issue-summary
  (fn [_ issue]
    (:schema-keyword issue)))

(defmulti issue-details
  "Details for validation issue, dispatches on json schema-keyword"
  (fn [_ {:keys [schema-keyword]}]
    schema-keyword))

(defmethod json-schema-issue-summary "type"
  [_ {:keys [schema schema-keyword instance]}]
  [:span
   "Expected " [:strong "type"] " "
   [:code.expected (get schema schema-keyword)]
   ", got "
   [:code.got (value-type instance)]])

(defmethod json-schema-issue-summary "required"
  [_ {:keys [hints]}]
  [:span
   "Missing " [:strong "required"] " field(s): "
   (interpose ", "
              (map #(vector :code.expected %)
                   (:missing hints)))])

(defmethod json-schema-issue-summary "enum"
  [_ {:keys [schema schema-keyword path]}]
  [:span
   "Expected " [:strong "enum"]
   " value for " [:code (last path)]
   " of: "
   (interpose ", "
              (map #(vector :code.expected %)
                   (get schema schema-keyword)))])

(defn json-schema-title
  [{:strs [title $ref] :as schema}]
  (cond
    title
    [:q title]

    $ref
    [:code.ref $ref]

    :else
    [:code.expected (pretty-json schema)]))

(defmethod json-schema-issue-summary "oneOf"
  [openapi {:keys [schema schema-keyword hints sub-issues]}]
  [:span
   "Expected exactly " [:strong "one of"] ": "
   (interpose ", "
              (map json-schema-title
                   (get schema schema-keyword)))
   ". Validaded against " (:ok-count hints)
   [:details
    (if (zero? (:ok-count hints))
      (->> sub-issues
           (keep-indexed
            (fn [i issues]
              (when (seq issues)
                [:li "Invalid " (-> schema
                                    (get-in [schema-keyword i])
                                    (json-schema-title)) ". " (count issues) " issues:"
                 (issue-snippets-list openapi issues)])))
           (into [:ul]))
      [:span
       "But instance validates to " [:b "all"] " the schemas!"])]])

(defmethod issue-details "oneOf"
  [openapi {:keys [schema schema-keyword hints sub-issues]}]
  (if (zero? (:ok-count hints))
    (->> sub-issues
         (keep-indexed
          (fn [i issues]
            (when (seq issues)
              [:li "Invalid " (-> schema
                                  (get-in [schema-keyword i])
                                  (json-schema-title)) ". " (count issues) " issues:"
               (issue-snippets-list openapi issues)])))
         (into [:ul]))
    [:span
     "But instance validates to " [:b "all"] " the schemas!"]))

(defmethod json-schema-issue-summary "anyOf"
  [openapi {:keys [schema schema-keyword sub-issues]}]
  [:span
   "Expected " [:strong "any of"] ": "
   (interpose ", "
              (map json-schema-title
                   (get schema schema-keyword)))
   ". Validaded against none."
   [:details
    (->> sub-issues
         (keep-indexed
          (fn [i issues]
            (when (seq issues)
              [:li "Invalid " (-> schema
                                  (get-in [schema-keyword i])
                                  (json-schema-title)) ". " (count issues) " issues:"
               (issue-snippets-list openapi issues)])))
         (into [:ul]))]])

(defmethod issue-details "anyOf"
  [openapi {:keys [schema schema-keyword sub-issues]}]
  (->> sub-issues
       (keep-indexed
        (fn [i issues]
          (when (seq issues)
            [:li "Invalid " (-> schema
                                (get-in [schema-keyword i])
                                (json-schema-title)) ". " (count issues) " issues:"
             (issue-snippets-list openapi issues)])))
       (into [:ul])))

(defmethod json-schema-issue-summary "contains"
  [openapi {:keys [schema schema-keyword sub-issues instance]}]
  [:span
   "Expected collection of " (count instance)  " items to contain " (json-schema-title (get schema schema-keyword))])

(defmethod issue-details "contains"
  [openapi {:keys [instance schema schema-keyword sub-issues]}]
  (if (seq instance)
    (->> sub-issues
          (map-indexed
           (fn [i issues]
             (if (seq issues)
               [:dt "Item " i " has " (count issues) " issues:"
                [:du (issue-snippets-list openapi issues)]])))
          (into [:dl]))
    [:dl [:dt "Collection is empty!"]]))

(defmethod json-schema-issue-summary "maxItems"
  [openapi {:keys [schema schema-keyword]}]
  [:span
   "Expected collection to contain no more than " (schema "maxItems") " items"])

(defmethod json-schema-issue-summary "minItems"
  [openapi {:keys [schema schema-keyword]}]
  [:span
   "Expected collection to contain least " (schema "minItems") " items"])

(defmethod json-schema-issue-summary :default
  [_ {:keys [schema-keyword]}]
  [:span
   "JSON Schema Issue: " [:code.schema-keyword schema-keyword]])

(defmulti issue-summary
  "One sentance summary of issue"
  (fn [_ issue]
    (:issue issue)))

(defmethod issue-summary "schema-validation-error"
  [openapi issue]
  (json-schema-issue-summary openapi issue))

(defmethod issue-summary :default
  [_ {:keys [issue]}]
  [:span
   "Issue: " [:code.issue-type issue]])

(defmethod issue-summary "status-error"
  [_ {:keys [hints instance]}]
  [:span
   [:strong "Status error"] " Expected one of: " (string/join ", " (:ranges hints)) ", got " instance])

(defn- issue-example
  [openapi {:keys [schema-keyword canonical-schema-path]}]
  (when schema-keyword
    ;; schema-keyword issues have a full json-schema as the parent of
    ;; the schema keyword
    (example/example openapi (subvec canonical-schema-path 0 (dec (count canonical-schema-path))))))

(defn- path->hiccup [coll]
  [:span.path (interpose " / " (map #(vector :code %) coll))])


;; this also works for non-json-schema issue types
(defmethod issue-details :default
  [openapi {:keys [instance path schema-path] :as issue}]
  [:dl
   (for [[label value]
         {"Value"               (pretty-json instance
                                             :max-depth max-value-depth
                                             :max-length max-value-length)
          "Example from schema" (pretty-json (issue-example openapi issue))
          "Path in body"        (path->hiccup path)
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

(defn issue-snippet
  "Display issue with summary and details"
  [openapi {:keys [path] :as issue} i]
  [:details.issue (when (zero? i)
                    {:open true})
   [:summary.issue (issue-summary openapi issue) " at " (path->hiccup path)]
   (issue-details openapi issue)])

(defn- interaction-snippet
  "Display interaction with method, path, summary and details"
  [openapi {:keys [interaction path] :as issue} i]
  [:details.interaction (when (zero? i) {:open true})
   [:summary
    [:div.headline (interaction-summary interaction)]
    [:div.summary (issue-summary openapi issue) " at " (path->hiccup path)]]
   (issue-details openapi issue)])

(defn- issue-snippets-list
  [openapi issues]
  [:ol
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
  [:ul
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
   [:p "(sorted by percentage of issues)"]

   (for [[[_ path] interactions]
         (->> interactions
              (group-by :operation-path)
              (sort-by (fn [[path interactions]]
                         [(* -1 (score-percent interactions)) path])))]
     [:section.interaction-path
      [:h3 path]
      (let [n             (count interactions)
            n-with-issues (->> interactions (filter :issues) (count))]
        [:div
         [:div.summary (- n n-with-issues) " of " n " observations have no issues."]

         (when (pos? n-with-issues)
           (let [issues-by-schema-path (->> interactions
                                            (mapcat (fn [interaction]
                                                      (map #(assoc % :interaction interaction)
                                                           (:issues interaction))))
                                            (group-by :canonical-schema-path)
                                            (sort-by (fn [[path issues]]
                                                       [(* -1 (count issues)) path])))
                 n-issue-types  (count issues-by-schema-path)]
             [:div.summary
              [:p
               (if (> n-issue-types 1)
                 (format "%d different validation issues"
                         n-issue-types)
                 "1 validation issue")
               " (by schema path):"]
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
                     [:div.summary (count issues) " issues in "
                      (count (filter (fn [{:keys [issues]}]
                                       (some #(= schema-path (:canonical-schema-path %)) issues))
                                     interactions)) " observations"]]]
                   (interaction-snippet-list openapi issues)]])

               (when (> (count issues-by-schema-path) max-issues-per-schema-path)
                 [:li.and-more
                  "and "
                  (- (count issues-by-schema-path)
                     max-issues-per-schema-path)
                  " more.."])]]))])])])

(defn- raw-css [css]
  (hiccup.util/raw-string "/*<![CDATA[*/\n" css "/*]]>*/"))

(defn report
  [openapi interactions]
  (hiccup.page/html5
   [:html
    [:head [:title report-title]
     [:style (-> css-resource (io/resource) (slurp) (raw-css))]]
    [:body
     [:header
      [:h1 report-title]]

     [:main
      [:section.general
       (interactions-summary interactions)
       (kpis-section interactions)]
      (per-path-section openapi interactions)]

     [:footer
      "This report was generated at " (java.util.Date.)]]]))

(defn -main
  [& args]
  (System/setProperty "file.encoding" "UTF-8") ;; Force, for windows
  (let [{:keys [errors summary options arguments]} (parse-opts args cli-options)
        {:keys [openapi]} options]
    (when (seq errors)
      (println errors)
      (println summary)
      (System/exit 1))
    (binding [*out* (io/writer (:write-to options) :encoding "UTF-8")]
      (println
       ;; str needed to coerce hiccup "rawstring"
       (str (report (data.json/read-json (io/reader openapi :encoding "UTF-8") false)
                    (with-open [in (java.io.PushbackReader. (io/reader (first arguments)))]
                      (edn/read in))))))))
