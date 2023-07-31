(ns nl.jomco.eduhub-validator.spider
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.tools.cli :refer [parse-opts]]
            [nl.jomco.openapi.v3.path-matcher :refer [paths-matcher]]
            [nl.jomco.openapi.v3.validator :as validator]
            [nl.jomco.spider :as spider]
            [ring.middleware.params :as params]))

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
  [{:keys [request response]}]
  {:request  (-> request
                 (params/assoc-query-params "UTF-8"))
   :response response})

(def cli-options
  [["-o" "--openapi OPENAPI-PATH" "OpenAPI specification"
    :missing "openapi path is required"]
   ["-r" "--rules RULES-PATH" "Spidering rules"
    :missing "rules path is required"]
   ["-u" "--base-url URL" "Base URL of service to validate"
    :missing "service-base-url is required"]])

(defn spider-and-validate
  [{:keys [openapi rules base-url]}]
  (let [{:keys [rules seeds]} (read-edn rules)
        seeds                 (map (fn [{:keys [path] :as seed}]
                                     (assoc seed :url (spider/make-url base-url path)))
                                   seeds)
        openapi-spec          (read-spec openapi)
        validate              (-> (validator/validator-context openapi-spec {})
                                  (validator/interaction-validator))
        matcher               (paths-matcher (keys (get openapi-spec "paths")))
        op-path               (fn [interaction]
                                (when-let [template (:template (matcher (:uri (:request interaction))))]
                                  [:paths template (:method (:request interaction))]))]
    (->> (iterate (fn [state]
                    (spider/step state rules))
                  (spider/step {:pool (set seeds), :seen #{}} rules))
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
    (prn (spider-and-validate options))))
