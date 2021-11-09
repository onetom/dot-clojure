(ns dev
  "Invoked via load-file from ~/.clojure/deps.edn, this
  file looks at what tooling you have available on your
  classpath and starts a REPL."
  (:require [clojure.repl :refer [demunge]]
            [clojure.string :as str]))

(defn up-since
  "Return the date this REPL (Java process) was started."
  []
  (java.util.Date. (- (.getTime (java.util.Date.))
                      (.getUptime (java.lang.management.ManagementFactory/getRuntimeMXBean)))))
(when-not (resolve 'requiring-resolve)
  (throw (ex-info ":dev/repl and dev.clj require at least Clojure 1.10"
                  *clojure-version*)))

(defn- ->long
  "Attempt to parse a string as a Long and return nil if it fails."
  [s]
  (try
    (and s (Long/parseLong s))
    (catch Throwable _)))

(defn- ellipsis [s n] (if (< n (count s)) (str "..." (subs s (- (count s) n))) s))

(comment
  (ellipsis "this is a long string" 10)
  (ellipsis "short string" 20)
  ,)

(defn- clean-trace
  "Given a stack trace frame, trim class and file to the rightmost 24
  chars so they make a nice, neat table."
  [[c f file line]]
  [(symbol (ellipsis (-> (name c)
                         (demunge)
                         (str/replace #"--[0-9]{1,}" ""))
                     24))
   f
   (ellipsis file 24)
   line])

(comment
  (mapv clean-trace (-> (ex-info "Test" {}) (Throwable->map) :trace))
  ,)

(defn- start-repl
  "Ensures we have a DynamicClassLoader, in case we want to use
  add-libs from the add-lib3 branch of clojure.tools.deps.alpha (to
  load new libraries at runtime).

  If Jedi Time is on the classpath, require it (so that Java Time
  objects will support datafy/nav).

  Attempts to start a Socket REPL server. The port is selected from:
  * SOCKET_REPL_PORT environment variable if present, else
  * socket-repl-port JVM property if present, else
  * .socket-repl-port file if present, else
  * defaults to 0 (which will automatically pick an available port)
  Writes the selected port back to .socket-repl-port for next time.

  Then pick a REPL as follows:
  * if Figwheel Main is on the classpath then start that, else
  * if Rebel Readline is on the classpath then start that, else
  * start a plain ol' Clojure REPL."
  []
  ;; set up the DCL:
  (try
    (let [cl (.getContextClassLoader (Thread/currentThread))]
      (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))
    (catch Throwable t
      (println "Unable to establish a DynamicClassLoader!")
      (println (ex-message t))))

  ;; jedi-time?
  (try
    (require 'jedi-time.core)
    (println "Java Time is Datafiable...")
    (catch Throwable _))

  ;; socket repl handling:
  (let [s-port (or (->long (System/getenv "SOCKET_REPL_PORT"))
                   (->long (System/getProperty "socket-repl-port"))
                   (->long (try (slurp ".socket-repl-port") (catch Throwable _)))
                   0)]
    ;; if there is already a 'repl' Socket REPL open, don't open another:
    (when-not (get (deref (requiring-resolve 'clojure.core.server/servers)) "repl")
      (try
        (let [server-name (str "REPL-" s-port)]
          ((requiring-resolve 'clojure.core.server/start-server)
           {:port s-port :name server-name
            :accept 'clojure.core.server/repl})
          (let [s-port' (.getLocalPort
                         (get-in @(requiring-resolve 'clojure.core.server/servers)
                                 [server-name :socket]))]
            (println "Selected port" s-port' "for the Socket REPL...")
            ;; write the actual port we selected (for Chlorine/Clover to read):
            (spit ".socket-repl-port" (str s-port'))))
        (catch Throwable t
          (println "Unable to start the Socket REPL on port" s-port)
          (println (ex-message t))))))

  ;; if Portal and clojure.tools.logging are both present,
  ;; cause all (successful) logging to also be tap>'d:
  (try
    ;; if we have Portal on the classpath...
    (require 'portal.console)
    ;; ...then install a tap> ahead of tools.logging:
    (let [log-star (requiring-resolve 'clojure.tools.logging/log*)
          log*-fn  (deref log-star)]
      (alter-var-root log-star
                      (constantly (fn [logger level throwable message]
                                    ;; only called for enabled log levels:
                                    (tap>
                                     {:form     '()
                                      :level    level
                                      :result   (or throwable message)
                                      :ns       (symbol (str *ns*))
                                      :file     *file*
                                      :line     0
                                      :column   0
                                      :time     (java.util.Date.)
                                      :runtime  :clj})
                                    (log*-fn logger level throwable message)))))
    (catch Throwable _))

  ;; select and start a main REPL:
  (let [[repl-name repl-fn]
        (or (try
              (let [figgy (requiring-resolve 'figwheel.main/-main)]
                ["Figwheel Main" #(figgy "-b" "dev" "-r")])
              (catch Throwable _))
            (try ["Rebel Readline" (requiring-resolve 'rebel-readline.main/-main)]
                 (catch Throwable _))
            ["clojure.main" (resolve 'clojure.main/main)])]
    (println "Starting" repl-name "as the REPL...")
    (repl-fn)))

(start-repl)

;; ensure a smooth exit after the REPL is closed
(System/exit 0)
