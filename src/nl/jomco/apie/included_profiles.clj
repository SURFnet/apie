(ns nl.jomco.apie.included-profiles
  "Include configuration for specific profiles in the jar/binary"
  (:require [clojure.java.io :as io]))

(def profiles
  (some-> "included-profiles.txt"
          (io/resource)
          (io/reader :encoding "UTF-8")
          (line-seq)))
