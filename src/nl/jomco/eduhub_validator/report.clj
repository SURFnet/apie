(ns nl.jomco.eduhub-validator.report
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [hiccup.core :as hiccup]
            [hiccup.util :as hiccup-util]
            [nl.jomco.http-status-codes :as http-status]))

(def cli-options [])

(def h #(hiccup-util/escape-html %))

(defn pretty-json [v]
  [:pre.json (h (json/write-str v :indent true :escape-slash false))])

(defn json-summary
  "Returns a JSON onliner when it does not exceed 40 characters, otherwise `nil`."
  [v]
  (let [json (json/write-str v :indent false)]
    (when (< (count json) 40)
      json)))

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
              (map #(vector :strong (h %)) (sort server-names)))]]
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
   [:span.url (h url)]
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

(defmulti issue-summary :schema-keyword)

(defmethod issue-summary "type"
  [{:keys [schema schema-keyword instance]}]
  [:span.issue-summary
   "Expected " [:strong "type"] " "
   [:code.expected (get schema schema-keyword)]
   ", got "
   [:code.got (value-type instance)]])

(defmethod issue-summary "required"
  [{:keys [hints]}]
  [:span.issue-summary
   "Missing " [:strong "required"] " field(s): "
   (interpose ", "
              (map #(vector :code.expected (h %))
                   (:missing hints)))])

(defmethod issue-summary "enum"
  [{:keys [schema schema-keyword path]}]
  [:span.issue-summary
   "Expected " [:strong "enum"]
   " value for " [:code (last path)]
   " of: "
   (interpose ", "
              (map #(vector :code.expected (h %))
                   (get schema schema-keyword)))])

(defmethod issue-summary "oneOf"
  [{:keys [schema schema-keyword]}]
  [:span.issue-summary
   "Expected " [:strong "one of"] ": "
   (interpose ", "
              (map #(if-let [title (get % "title")]
                      [:q (h title)]
                      [:code.expected (h %)])
                   (get schema schema-keyword)))])

(defmethod issue-summary "anyOf"
  [{:keys [schema schema-keyword]}]
  [:span.issue-summary
   "Expected " [:strong "any of"] ": "
   (interpose ", "
              (map #(if-let [title (get % "title")]
                      [:q (h title)]
                      [:code.expected (h %)])
                   (get schema schema-keyword)))])

(defmethod issue-summary :default
  [{:keys [schema-keyword]}]
  [:span.issue-summary
   "Issue: " [:code.schema-keyword schema-keyword]])

(defn issue-details
  [{:keys [path schema-path] :as issue}]
  [:details.issue-details
   [:summary (issue-summary issue)]
   [:dl
    (for [[label path] {"Path in body" path
                        "Schema path"  schema-path}]
      [:div
       [:dt label]
       [:dd (interpose " / " (map #(vector :code (h %)) path))]])]
   ;; TODO handle sub-issues using issue-snippet
   (-> issue
       (dissoc :canonical-schema-path :instance :interaction :path :schema-path :schema-keyword)
       (pretty-json))])

(defn- instance-details [instance]
  (if-let [json (json-summary instance)]
    [:div.details.instance-details "Value: " [:code json]]
    [:details.instance-details
     [:summary "Value"]
     (pretty-json instance)]))

(defn- issue-snippet [{:keys [instance interaction] :as issue}]
  [:details
   [:summary (interaction-summary interaction)]
   [:div
    (issue-details issue)
    (instance-details instance)]])

(defn per-path-section [interactions]
  [:section
   [:h2 "Results per request path"]
   [:p "(sorted by percentage of issues)"]

   (for [[[_ path] interactions]
         (->> interactions
              (group-by :operation-path)
              (sort-by (fn [[path interactions]]
                         [(* -1 (score-percent interactions)) path])))]
     [:section
      [:h3.interaction-path (h path)]
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
                (for [[schema-path issues] issues-by-schema-path]
                  [:li
                   [:details
                    [:summary
                     [:span.schema-path (string/join "/" schema-path)]
                     ": "
                     [:span.count (count issues)]]
                    [:ul
                     (for [issue issues]
                       [:li (issue-snippet issue)])]]])]]]))])])])
(defn report
  [interactions]
  (hiccup/html
   [:html
    [:head [:title report-title]]
    [:style (-> css-resource (io/resource) (slurp))]
    [:body
     [:header
      [:h1 report-title]]

     [:main
      (interactions-summary interactions)
      (kpis-section interactions)
      (per-path-section interactions)]]]))

(defn -main
  [& args]
  (let [{:keys [errors summary _options arguments]} (parse-opts args cli-options)]
    (when (seq errors)
      (println errors)
      (println summary)
      (System/exit 1))
    (println
     (report
      (with-open [in (java.io.PushbackReader. (io/reader (first arguments)))]
        (edn/read in))))))
