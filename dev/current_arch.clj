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
