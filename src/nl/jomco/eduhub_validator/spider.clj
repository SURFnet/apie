(ns nl.jomco.eduhub-validator.spider
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.pprint :as pprint]
            [clojure.tools.cli :refer [parse-opts]]
            [nl.jomco.openapi.v3.path-matcher :refer [paths-matcher]]
            [nl.jomco.openapi.v3.validator :as validator]
            [nl.jomco.spider :as spider]
            [ring.middleware.params :as params]
            [clojure.java.io :as io]))

(defn read-edn
  [path]
  {:pre [path]}
  (edn/read-string (or (slurp path)
                       (throw (ex-info (str "Can't read " path)
                                       {:path path})))))

(defn read-spec
  [spec-path]
  (-> spec-path
      slurp
      (json/read-str)))

(defn fixup-interaction
  "Convert spider interaction to format expected by validator."
  [interaction]
  (-> interaction
      (update :request #(params/assoc-query-params % "UTF-8"))
      ;; remove java.net.URI - not needed and doesn't print cleanly
      (update :response dissoc :uri)))

(defn parse-header
  [s]
  (let [[k v] (string/split s #": +" 2)]
    (when-not v
      (throw (ex-info (str "Can't parse header '" s "'") {})))
    [(keyword (string/lower-case k)) v]))

(defn merge-header-values
  [v1 v2]
  (if (nil? v1)
    v2
    (if (vector? v1)
      (if (vector? v2)
        (into v1 v2)
        (conj v1 v2))
      (if (vector? v2)
        (into [v1] v2)
        [v1 v2]))))

(defn add-header
  [headers [k v]]
  (update-in headers [k] merge-header-values v))

(def cli-options
  [["-o" "--openapi OPENAPI-PATH" "OpenAPI specification"
    :missing "openapi path is required"]
   ["-r" "--rules RULES-PATH" "Spidering rules"
    :missing "rules path is required"]
   ["-w" "--write-to RESULT-PATH"]
   ["-u" "--base-url URL" "Base URL of service to validate"
    :missing "service-base-url is required"]
   ["-h" "--headers 'HEADER: VALUE'" "Additional header(s) to add to request "
    :default {}
    :multi true
    :parse-fn parse-header
    :update-fn add-header]
   ["-b" "--bearer-token TOKEN"
    "Add bearer token to request"
    :default nil]
   ["-a" "--basic-auth 'USER:PASS'" "Send basic authentication header"
    :default nil
    :parse-fn (fn [s]
                (let [[user pass] (string/split s #": *")]
                  (when-not pass
                    (throw (ex-info "Can't parse basic-auth" {:s s})))
                  {:user user
                   :pass pass}))]])

(defn merge-headers
  [headers1 headers2]
  (merge-with merge-header-values headers1 headers2))

(defn wrap-timing
  "Middleware adding timing info to interactor"
  [interactor]
  (fn [request]
    (let [start-at    (java.util.Date.)]
      (assoc (interactor request)
             :start-at start-at
             :finish-at (java.util.Date.)))))

(defn wrap-update-req
  "Middleware for updating request prior to sending"
  [interactor update-fn & args]
  (fn [request]
    (interactor (apply update-fn request args))))

(defn wrap-headers
  [interactor headers]
  (wrap-update-req interactor update :headers
                   (fn [existing-headers]
                     (merge-with merge-header-values existing-headers headers))))

(defn wrap-basic-auth
  [interactor basic-auth]
  (wrap-update-req interactor assoc :basic-auth basic-auth))

(defn wrap-bearer-token
  [interactor token]
  (wrap-headers interactor {:authorization (str "Bearer " token)}))

(defn mk-interact
  [{:keys [headers basic-auth bearer-token]}]
  (cond-> spider/interact
    :always
    (wrap-timing)

    (seq headers)
    (wrap-headers headers)

    basic-auth
    (wrap-basic-auth basic-auth)

    bearer-token
    (wrap-bearer-token bearer-token)))

(defn spider-and-validate
  [{:keys [openapi rules base-url] :as options}]
  (let [{:keys [rules seeds]} (read-edn rules)
        interact              (mk-interact options)
        seeds                 (map (fn [{:keys [path] :as seed}]
                                     (-> seed
                                         (assoc :url (spider/make-url base-url path))))
                                   seeds)
        openapi-spec          (read-spec openapi)
        validate              (-> (validator/validator-context openapi-spec {})
                                  (validator/interaction-validator))
        matcher               (paths-matcher (keys (get openapi-spec "paths")))
        op-path               (fn [interaction]
                                ;; TODO: Don't follow requests to out-of-spec urls
                                (when-let [template (:template (matcher (:uri (:request interaction))))]
                                  [:paths template (:method (:request interaction))]))
        follow?               (fn [{:keys [path uri url]}]
                                (-> (or path uri url)
                                    (string/replace #"http(s)?://[^/]+" "")
                                    matcher))]
    (->> (iterate (fn [state]
                    (spider/step state rules
                                 :interact interact
                                 :follow? follow?))
                  (spider/step {:pool (set (filter follow? seeds)), :seen #{}} rules
                               :interact interact
                               :follow? follow?))
         (take-while some?)
         (map :interaction)
         (map fixup-interaction)
         (map #(assoc %
                      :issues (validate % [])
                      :operation-path (op-path %))))))

(defn -main
  [& args]
  (let [{:keys [errors summary options]} (parse-opts args cli-options)]
    (when (seq errors)
      (println errors)
      (println summary)
      (System/exit 1))
    (binding [*out* (io/writer (:write-to options) :encoding "UTF-8")]
      (println "[")
      (run! pprint/pprint (spider-and-validate options))
      (println "]"))))
