(ns nl.jomco.eduhub-validator.report
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [hiccup2.core :as hiccup2]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.eduhub-validator.report.json :as json]
            [clojure.data.json :as data.json]
            [nl.jomco.openapi.v3.example :as example]))

(def cli-options
  [["-o" "--openapi OPENAPI-PATH" "OpenAPI specification"
    :missing "openapi path is required"]])

(def max-issues-per-schema-path 10)
(def max-issues 3)
(def max-value-depth 1)
(def max-value-length 120)

(defn pretty-json [v & opts]
  [:pre.json (apply json/to-s v opts)])

(defn with-issues [interactions]
  (filter :issues interactions))

(defn score-percent [interactions]
  (* 100.0 (/ (count (with-issues interactions))
              (count interactions))))

(defn score-summary [interactions]
  (let [score (score-percent interactions)]
    (if (zero? score)
      (str "🙂 no issues found!")
      (format "😢 %.1f%% (%d) observations have issues"
              score
              (count (with-issues interactions))))))


(def report-title "SURFeduhub validation report")
(def css-resource "style.css")

(defn interactions-summary [interactions]
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
     [:dl
      [:div
       [:dt "Server" (if (> (count server-names) 1) "s" "")]
       [:dd (interpose ", "
                       (map #(vector :strong %) (sort server-names)))]]
      [:div
       [:dt "Run time"]
       [:dd "From "[:strong start-at] " till " [:strong finish-at]]]]]))

(defn kpis-section [interactions]
  [:section.kpis
   [:h2 "KPIs"]
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

(defn- interaction-summary [{{:keys [method url]} :request
                             {:keys [status reason-phrase]} :response}]
  [:span.interaction-summary
   [:span.method (string/upper-case (name method))]
   " "
   [:span.url url]
   " → "

   [:span.status
    {:class (if (http-status/success-status? status)
              "status-success"
              "status-failure")}
    status
    " "
    reason-phrase]])

(defn value-type [v]
  (cond
    (sequential? v) "array"
    (boolean? v)    "boolean"
    (integer? v)    "integer"
    (map? v)        "object"
    (nil? v)        "null"
    (number? v)     "number"
    (string? v)     "string"))

(defmulti json-schema-issue-summary :schema-keyword)

(defmethod json-schema-issue-summary "type"
  [{:keys [schema schema-keyword instance]}]
  [:span.issue-summary
   "Expected " [:strong "type"] " "
   [:code.expected (get schema schema-keyword)]
   ", got "
   [:code.got (value-type instance)]])

(defmethod json-schema-issue-summary "required"
  [{:keys [hints]}]
  [:span.issue-summary
   "Missing " [:strong "required"] " field(s): "
   (interpose ", "
              (map #(vector :code.expected %)
                   (:missing hints)))])

(defmethod json-schema-issue-summary "enum"
  [{:keys [schema schema-keyword path]}]
  [:span.issue-summary
   "Expected " [:strong "enum"]
   " value for " [:code (last path)]
   " of: "
   (interpose ", "
              (map #(vector :code.expected %)
                   (get schema schema-keyword)))])

(defmethod json-schema-issue-summary "oneOf"
  [{:keys [schema schema-keyword]}]
  [:span.issue-summary
   "Expected " [:strong "one of"] ": "
   (interpose ", "
              (map #(if-let [title (get % "title")]
                      [:q title]
                      [:code.expected %])
                   (get schema schema-keyword)))])

(defmethod json-schema-issue-summary "anyOf"
  [{:keys [schema schema-keyword]}]
  [:span.issue-summary
   "Expected " [:strong "any of"] ": "
   (interpose ", "
              (map #(if-let [title (get % "title")]
                      [:q title]
                      [:code.expected %])
                   (get schema schema-keyword)))])

(defmethod json-schema-issue-summary :default
  [{:keys [schema-keyword]}]
  [:span.issue-summary
   "Issue: " [:code.schema-keyword schema-keyword]])

(defmulti issue-summary :issue)

(defmethod issue-summary "schema-validation-error"
  [issue]
  (json-schema-issue-summary issue))

(defmethod issue-summary :default
  [{:keys [issue]}]
  [:span.issue-summary
   "Issue: " [:code.issue-type issue]])

(defn issue-example
  [openapi {:keys [schema-keyword canonical-schema-path]}]
  (when schema-keyword
    ;; schema-keyword issues have a full json-schema as the parent of
    ;; the schema keyword
    (example/example openapi (subvec canonical-schema-path 0 (dec (count canonical-schema-path))))))

(defn issue-details
  [openapi {:keys [path schema-path] :as issue}]
  [:details.issue
   [:summary (issue-summary issue)]
   [:dl
    (for [[label path] {"Path in body" path
                        "Schema path"  schema-path}]
      [:div
       [:dt label]
       [:dd (interpose " / " (map #(vector :code %) path))]])
    (when-let [example (issue-example openapi issue)]
      [:div [:dt "Example"]
       [:dd (pretty-json example)]])
    ;; TODO handle sub-issues using issue-snippet
    [:dt "Issue data"]
    [:dd (-> issue
             (dissoc :canonical-schema-path :instance :interaction :path :schema-path :schema-keyword)
             (pretty-json))]]])

(defn- instance-details [instance i]
  [:details.instance (when (= 0 i) {:open true})
   [:summary "Invalid value"]
   (pretty-json instance
                :max-depth max-value-depth, :max-length max-value-length)])

(defn- issue-snippet [openapi {:keys [instance interaction] :as issue} i]
  [:details.interaction (when (= 0 i) {:open true})
   [:summary (interaction-summary interaction)]
   [:div
    (issue-details openapi issue)
    (instance-details instance i)]])

(defn per-path-section [openapi interactions]
  [:section
   [:h2 "Results per request path"]
   [:p "(sorted by percentage of issues)"]

   (for [[[_ path] interactions]
         (->> interactions
              (group-by :operation-path)
              (sort-by (fn [[path interactions]]
                         [(* -1 (score-percent interactions)) path])))]
     [:section
      [:h3.interaction-path path]
      (let [n             (count interactions)
            n-with-issues (->> interactions (filter :issues) (count))]
        [:dl
         [:div
          [:dt "Observations"]
          [:dd n]]

         [:div
          [:dt "Validation score"]
          [:dd (score-summary interactions)]]

         (when (pos? n-with-issues)
           (let [issues-by-schema-path (->> interactions
                                            (mapcat (fn [interaction]
                                                      (map #(assoc % :interaction interaction)
                                                           (:issues interaction))))
                                            (group-by :canonical-schema-path)
                                            (sort-by (fn [[path issues]]
                                                       [(* -1 (count issues)) path])))
                 n-issue-types  (count issues-by-schema-path)]
             [:div
              [:dt "Type of issues"]
              [:dd
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
                     [:span.schema-path (string/join "/" schema-path)]
                     ": "
                     [:span.count (count issues) " issues in "
                      (count (filter (fn [{:keys [issues]}]
                                       (some #(= schema-path (:canonical-schema-path %)) issues))
                                     interactions)) " observations"]]
                    [:ul
                     (for [[issue i]
                           (map vector
                                (take max-issues issues)
                                (iterate inc 0))]
                       [:li (issue-snippet openapi issue i)])
                     (when (> (count issues) max-issues)
                       [:li.and-more
                        "and "
                        (- (count issues) max-issues)
                        " more.."])]]])

                (when (> (count issues-by-schema-path) max-issues-per-schema-path)
                  [:li.and-more
                   "and "
                   (- (count issues-by-schema-path)
                      max-issues-per-schema-path)
                   " more.."])]]]))])])])

(defn report
  [openapi interactions]
  (hiccup2/html
   [:html
    [:head [:title report-title]]
    [:style (-> css-resource (io/resource) (slurp))]
    [:body
     [:header
      [:h1 report-title]]

     [:main
      (interactions-summary interactions)
      (kpis-section interactions)
      (per-path-section openapi interactions)]]]))

(defn -main
  [& args]
  (let [{:keys [errors summary options arguments]} (parse-opts args cli-options)
        {:keys [openapi]} options]
    (when (seq errors)
      (println errors)
      (println summary)
      (System/exit 1))
    (println
     ;; str needed to coerce hiccup "rawstring"
     (str (report (data.json/read-json (io/reader openapi) false)
           (with-open [in (java.io.PushbackReader. (io/reader (first arguments)))]
             (edn/read in)))))))
