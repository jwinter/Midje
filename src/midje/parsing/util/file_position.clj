(ns ^{:doc "Functions to help in finding the lines you care about."}
  midje.parsing.util.file-position
  (:use midje.parsing.util.core
        midje.parsing.util.zip
        midje.parsing.arrow-symbols
        [midje.parsing.util.zip :only [skip-to-rightmost-leaf]])
  (:require [clojure.zip :as zip]
            [clojure.string :as str]))
            

;; COMPILE-TIME POSITIONS.
;; For annotating forms with information retrieved at runtime.
;; For reporting syntax errors

(def fallback-line-number (atom (Integer. 0)))

(defn set-fallback-line-number-from [form]
  (reset! fallback-line-number (or (:line (meta form)) (Integer. 0))))

(letfn [(raw-arrow-line-number [arrow-loc]
          (try
            (or (-> arrow-loc zip/left zip/node meta :line )
              (-> arrow-loc zip/right zip/node meta :line )
              (inc (-> arrow-loc zip/prev zip/left zip/node meta :line )))
            (catch Throwable ex nil)))]

  (defn arrow-line-number [arrow-loc]
    (if-let [raw-lineno (raw-arrow-line-number arrow-loc)]
      (reset! fallback-line-number raw-lineno)
      (swap! fallback-line-number inc))))

(defn arrow-line-number-from-form
  "Form is of the form [ <function-call> => .* ]"
  [form]
  (-> form zip/seq-zip zip/down zip/right arrow-line-number))



(defn basename [string]
  (last (str/split string #"/")))

;; clojure.test sometimes runs with *file* bound to #"NO_SOURCE.*".
;; This corrects that by looking up the stack. Note that it
;; produces a reasonable result for the repl, because the stack
;; frame it finds has NO_SOURCE_FILE as its "filename". 
(defn current-file-name []
  (if-not (re-find #"NO_SOURCE" *file*)
    (basename *file*)
    (.getFileName (second (.getStackTrace (Throwable.))))))

(defn form-position [form]
  (list (current-file-name)  (:line (meta form))))

(defn compile-time-fallback-position []
  (list (current-file-name) @fallback-line-number))


;; RUNTIME POSITIONS
;; These are positions that determine the file or line at runtime.

(defmacro line-number-known 
  "Guess the filename of a file position, but use the given line number."
  [number]
  `[(current-file-name) ~number])

(letfn [(replace-loc-line [loc loc-with-line]
          (let [m (fn [loc] (meta (zip/node loc)))
                transferred-meta (if (contains? (m loc-with-line) :line )
                                   (assoc (m loc) :line (:line (m loc-with-line)))
                                   (dissoc (m loc) :line ))]
            (zip/replace loc (with-meta (zip/node loc) transferred-meta))))]
  
  (defn form-with-copied-line-numbers [line-number-source form]
    (loop [loc (zip/seq-zip form)
           line-loc (zip/seq-zip line-number-source)]
      (cond (zip/end? line-loc)
            (zip/root loc)
  
            (zip/branch? line-loc)
            (recur (zip/next (replace-loc-line loc line-loc))
                   (zip/next line-loc))
  
            ;; the form has a tree in place of a non-tree
            (zip/branch? loc)
              (recur (zip/next
                      (skip-to-rightmost-leaf (zip/down (replace-loc-line loc line-loc))))
                     (zip/next line-loc))
  
            :else
            (recur (zip/next loc)
                   (zip/next line-loc))))))


(defn positioned-form
  "Make sure the form is annotated with a line number, either
   its original or the given one. Takes either a number or form
   as a source."
  [form number-source]
  (cond (contains? (meta form) :line)
        form

        (integer? number-source)
        (vary-meta form assoc :line number-source)

        :else 
        (vary-meta form assoc :line (:line (meta number-source)))))
    
