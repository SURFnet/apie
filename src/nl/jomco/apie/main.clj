(ns nl.jomco.apie.main
  (:require [babashka.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [nl.jomco.apie.report :as report]
            [nl.jomco.apie.spider :as spider]
            [ring.util.codec :as codec])
  (:import java.net.URI
           [java.io File PushbackReader]))

(defn- parse-header
  [s]
  (let [[k v] (string/split s #": +" 2)]
    (when-not v
      (throw (ex-info (str "Can't parse header '" s "'") {})))
    [(keyword k) v]))

(defn- add-header
  [headers [k v]]
  (update-in headers [k] spider/merge-header-values v))

(defn valid-url?
  [u]
  (re-matches #"^https?://.*" u))

(defn valid-seed?
  "Seed argument should be a path or a full url matching base-url"
  [base-url s]
  (if (valid-url? s)
    (string/starts-with? s base-url)
    true))

(defn parse-seed
  [base-url s]
  {:pre [(valid-seed? base-url s)]}
  (let [s (string/replace s base-url "")
        u (URI. s)
        q (.getRawQuery u)]
    (cond-> {:method "get"
             :path (.getRawPath u)}
      q
      (assoc :query-params
             (codec/form-decode q)))))

(def cli-options
  [["-u" "--base-url BASE-URL" "Base URL of service to validate."
    :missing "BASE-URL is missing"
    :validate [valid-url? "Must be HTTP or HTTPS url"]]
   ["-o" "--observations OBSERVATIONS-PATH" "Path to read/write spidering observations."
    :id :observations-path
    :default "observations.edn"]
   ["-p" "--report REPORT-PATH" "Path to write report."
    :id :report-path
    :default "report.html"]
   ["-r" "--profile PROFILE" "Path to profile"
    :id :profile
    :missing "PROFILE is missing"]
   ["-S" "--no-spider" "Disable spidering (re-use observations from OBSERVATIONS-PATH)."
    :id :no-spider?
    :default false]
   ["-P" "--no-report" "Disable report generation (spidering will write observations)."
    :id :no-report?
    :default false]
   ["-h" "--add-header 'HEADER: VALUE'" "Add header to request. Can be used multiple times."
    :default {}
    :id :headers
    :multi true
    :parse-fn parse-header
    :update-fn add-header]
   ["-b" "--bearer-token TOKEN"
    "Add bearer token to request."
    :default nil]
   ["-M" "--max-total-requests N"
    "Maximum number of requests."
    :default ##Inf
    :parse-fn parse-long]
   ["-m" "--max-requests-per-operation N"
    "Maximum number of requests per operation in OpenAPI spec."
    :default ##Inf
    :parse-fn parse-long]
   ["-t" "--spider-timeout-millis N"
    "Maximum number of milliseconds before timeout of spider."
    :default ##Inf
    :parse-fn parse-long]
   ["-v" "--version"
    "Print version and exit."
    :id :print-version?]
   [nil "--help"
    "Print usage information and exit."]
   ["-a" "--basic-auth 'USER:PASS'" "Send basic authentication header."
    :default nil
    :parse-fn (fn [s]
                (let [[user pass] (string/split s #": *")]
                  (when-not pass
                    (throw (ex-info "Can't parse basic-auth" {:s s})))
                  {:user user
                   :pass pass}))]])

(defn usage
  [summary]
  (->> ["Apie 🙈 Service Validator"
        ""
        "A command-line tool to spider API endpoints and validate"
        "against OpenAPI v3 specs."
        ""
        "Usage: apie [OPTIONS] [SEED...]"
        ""
        "OPTIONS:"
        summary
        ""
        "SEEDs are full URLs matching BASE-URL, or paths relative to BASE-URL."
        ""
        "If SEEDs are not provided, uses seeds in profile."
        ""
        "To validate all reachable paths from a service, use"
        "apie --profile=some/profile.edn --base-url=http://example.com"
        ""
        "To validate one specific path, use"
        "apie -M1 --profile=some/profile.edn --base-url=http://example.com '/some/path?param=val'"]
       (string/join "\n")))

(defn version
  "Return app version"
  []
  (when-let [r (io/resource "nl/jomco/apie/version.txt")]
    (string/trim (slurp r))))

(defn file-or-resource
  "Return f as file if it exists, otherwise as resource.

  Returns nil if neither resource or file are present."
  [f]
  (assert f)
  (let [file (io/file f)]
    (if (.exists file)
      file
      (io/resource f))))

(defn file-parent
  "If f is a file, return its parent directory"
  [f]
  (when (instance? File f)
    (.getParentFile f)))

(defn read-edn
  [f]
  (with-open [in (PushbackReader. (io/reader f :encoding "UTF-8"))]
    (edn/read in)))

(defn read-json
  [f]
  (with-open [r  (io/reader f :encoding "UTF-8")]
    (json/read r {:key-fn str})))

(defn print-interaction
  [{{:keys [uri query-params method]} :request
    {:keys [status]} :response}]
  (let [uri (if query-params
              (str uri "?" (codec/form-encode query-params "UTF-8"))
              uri)]
    (println status (string/upper-case (name method)) uri)))

(defn spider
  [spec-data profile-data {:keys [base-url observations-path] :as options}]
  (println "Spidering" base-url)
  (with-open [w (io/writer observations-path :encoding "UTF-8")]
    (.write w "[")
    (run! #(do (print-interaction %)
               (pprint/pprint % w))
          (spider/spider-and-validate spec-data profile-data options))
    (.write w "]")))

(defn report
  [spec-data {:keys [observations-path report-path base-url]}]
  (println "Writing report to" report-path)
  (binding [*out* (io/writer report-path :encoding "UTF-8")]
    (println
     ;; str needed to coerce hiccup "rawstring"
     (str (report/report spec-data (read-edn observations-path) base-url)))))

(defn main
  [{:keys [no-spider? no-report? profile] :as options}]
  (let [profile*             (file-or-resource profile)
        parent-dir           (file-parent profile*)
        {:keys [openapi-spec]
         :as   profile-data} (read-edn profile*)
        ;; if the profile configuration is indicated by a file path
        ;; not in the current working directory, try to find the
        ;; corresponding openapi spec in the same directory.
        spec-data            (if parent-dir
                               (read-json (io/file parent-dir openapi-spec))
                               (read-json (file-or-resource openapi-spec)))]
    (when-not no-spider?
      (spider spec-data profile-data options))
    (when-not no-report?
      (report spec-data options))))

(defn -main
  [& args]
  (let [{:keys [errors summary options arguments]} (cli/parse-opts args cli-options)]
    (when (:help options)
      (println (usage summary))
      (System/exit 0))
    (when (:print-version? options)
      (println (version))
      (System/exit 0))
    (when (seq errors)
      (run! println errors)
      (println summary)
      (System/exit 1))
    (when-let [invalid-seeds (seq (remove #(valid-seed? (:base-url options) %) arguments))]
      (apply println "Invalid SEEDs:" invalid-seeds)
      (System/exit 1))

    (let [options (if (seq arguments)
                    (assoc options :seeds (map #(parse-seed (:base-url options) %) arguments))
                    options)]
      (main options))))
