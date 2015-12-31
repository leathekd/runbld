(ns runbld.util.data
  (:refer-clojure :exclude [bigdec])
  (:require [clojure.string :as str]
            [clojure.walk]
            [slingshot.slingshot :refer [throw+]]))

;; http://dev.clojure.org/jira/browse/CLJ-1468
(defn deep-merge
  "Like merge, but merges maps recursively."
  {:added "1.7"}
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))

(defn deep-merge-with
  "Like merge-with, but merges maps recursively, applying the given fn
  only when there's a non-map at a particular level."
  {:added "1.7"}
  [f & maps]
  (apply
   (fn m [& maps]
     (if (every? map? maps)
       (apply merge-with m maps)
       (apply f maps)))
   maps))

(defn safe-div
  ([m n]
   (if (and m n (zero? m) (zero? n))
     1.0
     (if (and n (pos? n))
       (float (/ m n))
       0.0))))

(defn bigdec
  "Set f to n places after the decimal."
  ([f]
   (clojure.core/bigdec f))
  ([f n]
   (when f
     (-> (clojure.core/bigdec f)
         (.setScale n BigDecimal/ROUND_HALF_UP)))))

(defn scaled-percent
  ([m n]
   (int
    (* 10000 (bigdec (safe-div m n))))))

(defn unscaled-percent
  ([n]
   (bigdec (/ n 100))))

(defn strip-trailing-slashes [s]
  (when s
    (if-let [[_ rst] (re-find #"^/+(.*)"
                              (->> s str/trim str/reverse))]
      (str/reverse rst)
      s)))

(defn as-int [value]
  (when value
    (cond
      (integer? value) value
      (and
       (string? value)
       (re-find #"\d+" value)) (Integer/parseInt value)
      :else (throw+
             {:type ::error
              :msg (format
                    "don't know how to make an integer out of: %s (%s)"
                    (pr-str value)
                    (type value))}))))
