(ns nl.jomco.eduhub-validator.spider
  (:require [nl.jomco.openapi.v3.path-matcher :refer [paths-matcher]]
            [nl.jomco.openapi.v3.validator :as validator]
            [nl.jomco.spider :as spider]
            [ring.middleware.params :as params]))

(defn fixup-interaction
  "Convert spider interaction to format expected by validator."
  [interaction]
  (-> interaction
      (update :request #(params/assoc-query-params % "UTF-8"))
      ;; remove java.net.URI - not needed and doesn't print cleanly
      (update :response dissoc :uri)))

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
  [openapi-spec {:keys [rules seeds] :as _rules} {:keys [base-url] :as options}]
  (let [interact (mk-interact options)
        seeds    (map (fn [{:keys [path] :as seed}]
                        (-> seed
                            (assoc :url (spider/make-url base-url path))))
                      seeds)
        validate (-> (validator/validator-context openapi-spec {})
                     (validator/interaction-validator))
        matcher  (paths-matcher (keys (get openapi-spec "paths")))
        op-path  (fn [interaction]
                   (when-let [template (:template (matcher (:uri (:request interaction))))]
                     [:paths template (:method (:request interaction))]))]
    (->> (iterate (fn [state]
                    (spider/step state rules
                                 :interact interact))
                  (spider/step {:pool (set seeds), :seen #{}} rules
                               :interact interact))
         (take-while some?)
         (map :interaction)
         (map fixup-interaction)
         (map #(assoc %
                      :issues (validate % [])
                      :operation-path (op-path %))))))
