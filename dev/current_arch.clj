;; SPDX-FileCopyrightText: 2024, 2025 SURF B.V.
;; SPDX-License-Identifier: EPL-2.0 WITH Classpath-exception-2.0
;; SPDX-FileContributor: Joost Diepenmaat

(ns current-arch
  (:require [clojure.string :as string]))

(def systems
  {"win"   "windows"
   "mac"   "macos"
   "linux" "linux"})

(def system
  (let [n (-> (System/getProperty "os.name" "unknown")
              (string/lower-case))]
    (some (fn [[sub os]]
            (when (string/index-of n sub)
              os))
          systems)))

(println (str system "-" (System/getProperty "os.arch" "unknown")))
