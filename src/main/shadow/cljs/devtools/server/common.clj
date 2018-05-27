(ns shadow.cljs.devtools.server.common
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]
    [cognitect.transit :as transit]
    [shadow.build]
    [shadow.build.api :as cljs]
    [shadow.build.classpath :as build-classpath]
    [shadow.build.npm :as build-npm]
    [shadow.build.babel :as babel]
    [shadow.cljs.devtools.plugin-manager :as plugin-mgr])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.util.concurrent Executors)))

(def app-config
  {:edn-reader
   {:depends-on []
    :start
    (fn []
      (fn [input]
        (cond
          (instance? String input)
          (edn/read-string input)
          (instance? InputStream input)
          (edn/read input)
          :else
          (throw (ex-info "dunno how to read" {:input input})))))
    :stop (fn [reader])}

   :cache-root
   {:depends-on [:config]
    :start (fn [{:keys [cache-root]}]
             (io/file cache-root))
    :stop (fn [cache-root])}

   :transit-str
   {:depends-on []
    :start
    (fn []
      (fn [data]
        (let [out (ByteArrayOutputStream. 4096)
              w (transit/writer out :json)]
          (transit/write w data)
          (.toString out)
          )))

    :stop (fn [x])}

   :plugin-manager
   {:depends-on []
    :start plugin-mgr/start
    :stop plugin-mgr/stop}

   :build-executor
   {:depends-on [:config]
    :start
    (fn [{:keys [compile-threads] :as config}]
      (let [n-threads
            (or compile-threads
                (.. Runtime getRuntime availableProcessors))]
        (Executors/newFixedThreadPool n-threads)))
    :stop
    (fn [ex]
      (.shutdown ex))}

   :classpath
   {:depends-on [:cache-root]
    :start (fn [cache-root]
             (-> (build-classpath/start cache-root)
                 (build-classpath/index-classpath)))
    :stop build-classpath/stop}

   :npm
   {:depends-on [:config]
    :start build-npm/start
    :stop build-npm/stop}

   :babel
   {:depends-on []
    :start babel/start
    :stop babel/stop}})

(defn get-system-config [{:keys [server-runtime plugins]}]
  (reduce
    (fn [config plugin]
      (try
        (require plugin)
        (let [plugin-var-name
              (symbol (name plugin) "plugin")

              plugin-kw
              (keyword (name plugin) "plugin")

              ;; FIXME: should eventually move this to classpath edn files and discover from there
              plugin-var
              (find-var plugin-var-name)

              {:keys [requires-server start stop] :as plugin-config}
              @plugin-var]

          (if (and requires-server (not server-runtime))
            config
            ;; wrapping the start/stop fns to they don't take down the entire system if they fail
            (let [safe-config
                  (assoc plugin-config
                    :start
                    (fn [& args]
                      (try
                        (apply start args)
                        (catch Exception e
                          (log/warnf e "failed to start plugin: %s" plugin)
                          ::error)))
                    :stop
                    (fn [instance]
                      (when (not= ::error instance)
                        (try
                          (cond
                            (and (nil? stop) (nil? instance))
                            ::ok

                            (nil? stop)
                            (log/warnf "plugin: %s returned something in start but did not provide a stop method" plugin)

                            :else
                            (stop instance))

                          (catch Exception e
                            (log/warnf e "failed to stop plugin: %s" plugin))))))]

              (assoc config plugin-kw safe-config))))
        (catch Exception e
          (log/warnf e "failed to load plugin: %s" plugin)
          config)))
    app-config
    plugins))
