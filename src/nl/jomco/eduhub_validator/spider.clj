(ns nl.jomco.eduhub-validator.spider
  (:require [nl.jomco.openapi.v3.path-matcher :refer [paths-matcher]]
            [nl.jomco.openapi.v3.validator :as validator]
            [nl.jomco.spider :as spider]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [babashka.http-client :as http-client]))

(defn fixup-request
  "Convert spider request to format expected by validator"
  [{:keys [path] :as request}]
  (-> request
      (dissoc :path)
      (assoc :uri path)))

(defn fixup-interaction
  "Convert spider interaction to format expected by validator."
  [interaction]
  (-> interaction
      (update :request fixup-request)
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
  "Middleware adding timing info to exec-request"
  [f]
  (fn [request]
    (let [start-at (java.util.Date.)]
      (assoc (f request)
             :start-at start-at
             :finish-at (java.util.Date.)))))

(defn wrap-update-req
  "Middleware for updating request prior to sending"
  [f update-fn & args]
  (fn [request]
    (f (apply update-fn request args))))

(defn wrap-headers
  [f headers]
  (wrap-update-req f update :headers
                   (fn [existing-headers]
                     (merge-with merge-header-values existing-headers headers))))

(defn wrap-basic-auth
  [f basic-auth]
  (wrap-update-req f assoc :basic-auth basic-auth))

(defn wrap-bearer-token
  [f token]
  (wrap-headers f {:authorization (str "Bearer " token)}))

(defn fixup-babashka-request
  [req]
  (-> req
      (assoc :uri (select-keys req [:host :port :path :scheme]))
      (dissoc :host :port :path :scheme)))

(defn fixup-validator-request
  [req]
  (assoc req :uri (:path req)))

(defn wrap-client
  [f client]
  (fn [request]
    (-> request
        (fixup-babashka-request)
        (assoc :client client
               :throw false)
        (f)
        (dissoc :request))))

(defn wrap-base-url
  "Ensure base-url info is added to request."
  [f base-url]
  (let [u            (java.net.URL. base-url)
        base-request {:host   (.getHost u)
                      :port   (.getPort u)
                      :path   "/"
                      :scheme (.getProtocol u)}
        base-path    (.getPath u)
        path-prefix (if (= base-path "/")
                      ""
                      base-path)]
    (fn [{:keys [path] :as request}]
      (f (-> base-request
             (merge request)
             (assoc :path (str path-prefix path)))))))

(defn json-type?
  [r]
  (string/starts-with? (get-in r [:headers "content-type"] "") "application/json"))

(defn wrap-json-body
  [f]
  (fn [request]
    (let [request (if (json-type? request)
                    (update request :body json/write-str)
                    request)]
      (let [response (f request)]
        (if (json-type? response)
          (update response :body json/read-str)
          response)))))

(defn wrap-max-requests-per-operation
  [f max-requests-per-operation op-path]
  (let [counters (atom {})]
    (fn [request]
      (let [operation (op-path request)
            n (get @counters operation 0)]
        (if (< n max-requests-per-operation)
          (do (swap! counters update operation (fnil inc 0))
              (f request))
          ::spider/skip)))))

(defn wrap-max-requests
  [f max-requests]
  (let [counter (atom 0)]
    (fn [request]
      (if (< @counter max-requests)
        (do (swap! counter inc)
            (f request))
        ::spider/skip))))

(defn mk-exec-request
  [{:keys [base-url headers basic-auth bearer-token]}]
  (cond-> http-client/request
    :always
    (wrap-client (http-client/client (assoc http-client/default-client-opts
                                            :follow-redirects :never)))

    :always
    (wrap-base-url base-url)

    :always
    (wrap-timing)

    :always
    (wrap-json-body)

    (seq headers)
    (wrap-headers headers)

    basic-auth
    (wrap-basic-auth basic-auth)

    bearer-token
    (wrap-bearer-token bearer-token)))

(defn path
  [{:keys [uri path url]}]
  (or path uri (.getPath (java.net.URI/create url))))

(defn spider-and-validate
  [openapi-spec
   {:keys [rules seeds] :as _rules}
   {:keys [base-url max-requests-per-operation max-total-requests] :as options}]
  (let [
        validate (-> (validator/validator-context openapi-spec {})
                     (validator/interaction-validator))
        matcher  (paths-matcher (keys (get openapi-spec "paths")))
        op-path  (fn [request]
                   (when-let [template (:template (matcher (path request)))]
                     [:paths template (:method request)]))
        exec-request (-> (mk-exec-request options)
                         (wrap-max-requests max-total-requests)
                         (wrap-max-requests-per-operation max-requests-per-operation op-path))]

    (->> (spider/spider {:rules rules :seeds seeds :exec-request exec-request})
         (map fixup-interaction)
         (map #(assoc %
                      :issues (validate % [])
                      :operation-path (op-path (:request %)))))))
