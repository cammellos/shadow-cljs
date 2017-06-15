(ns shadow.cljs.devtools.client.env
  (:require [goog.object :as gobj]))

(def x 1)

(defonce client-id (random-uuid))

(goog-define enabled false)

(goog-define autoload true)

(goog-define module-format "goog")

(goog-define before-load "")

(goog-define after-load "")

(goog-define reload-with-state false)

(goog-define build-id "")

(goog-define proc-id "")

(goog-define repl-host "")

(goog-define repl-port 8200)

(defn ws-url [client-type]
  {:pre [(keyword? client-type)]}
  (str "ws://" repl-host ":" repl-port "/worker/ws/" build-id "/" proc-id "/" client-id "/" (name client-type)))

(defn files-url []
  (str "//" repl-host ":" repl-port "/worker/files/" build-id "/" proc-id "/" client-id))


(def repl-print-fn pr-str)

(defn repl-call [repl-expr repl-error]
  (let [result {:type :repl/result}]
    (try
      (let [ret (repl-expr)]
        (set! *3 *2)
        (set! *2 *1)
        (set! *1 ret)

        (try
          (assoc result
            :value (repl-print-fn ret))
          (catch :default e
            (js/console.log "encoding of result failed" e ret)
            (assoc result :error "ENCODING FAILED"))))
      (catch :default e
        (set! *e e)
        (repl-error result e)
        ))))

