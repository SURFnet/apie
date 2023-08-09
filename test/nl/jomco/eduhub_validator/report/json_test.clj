(ns nl.jomco.eduhub-validator.report.json-test
  (:require [clojure.test :refer [deftest is]]
            [nl.jomco.eduhub-validator.report.json :as sut]))

(deftest to-s
  (is (= "1" (sut/to-s 1)))
  (is (= "\"1\"" (sut/to-s "1")))
  (is (= "null" (sut/to-s nil)))
  (is (= "true" (sut/to-s true)))

  (is (= "{}"
         (sut/to-s {})))
  (is (= "{\n  1: 2,\n  3: 4\n}"
         (sut/to-s {1 2 3 4})))
  (is (= "{\n  1: {\n    \"a\": 2,\n    \"b\": 3\n  },\n  4: 5\n}"
         (sut/to-s {1 {:a 2, :b 3} 4 5})))

  (is (= "[]"
         (sut/to-s [])))
  (is (= "[\n  1\n]"
         (sut/to-s [1])))
  (is (= "[\n  1,\n  2\n]"
         (sut/to-s [1 2])))

  (is (= "{\n  1: [\n    \"a\",\n    2,\n    {\n      \"b\": 3\n    }\n  ],\n  4: 5\n}"
         (sut/to-s {1 [:a 2, {:b 3}] 4 5}))))
