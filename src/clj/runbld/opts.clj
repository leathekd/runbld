(ns runbld.opts
  (:require [clojure.spec :as s]
            [slingshot.slingshot :refer [throw+]])
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [environ.core :as environ]
            [runbld.env :as env]
            [runbld.java :as java]
            [runbld.store :as store]
            [runbld.util.data :refer [deep-merge-with deep-merge]]
            [runbld.util.date :as date]
            [runbld.io :as io]
            [runbld.version :as version]))

(s/def ::job-name string?)

(defn windows? []
  (.startsWith (System/getProperty "os.name") "Windows"))

(def config-file-defaults
  {:es
   {:url "http://localhost:9200"
    :build-index   "build"
    :failure-index "failure"
    :log-index     "log"
    :http-opts {:insecure? false}
    :max-index-bytes store/MAX_INDEX_BYTES
    :bulk-timeout-ms 2000
    :bulk-size 500}

   :s3
   {:bucket "test.example.com"
    :prefix "/"
    :access-key "key"
    :secret-key "secret"}

   :java
   {}

   :process
   {:inherit-exit-code true
    :inherit-env       false
    :cwd (System/getProperty "user.dir")
    :stdout ".stdout.log"
    :stderr ".stderr.log"
    :output ".output.log"
    :env {}}

   :email
   {:host "localhost"
    :port 587
    :tls true
    :template-txt "templates/email.mustache.txt"
    :template-html "templates/email.mustache.html"
    :text-only false
    :max-failure-notify 10
    :disable false}

   :slack
   {:first-success true
    :success true
    :failure true
    :template "templates/slack.mustache.json"
    :disable false}})

(defn merge-profiles
  [job-name profiles]
  (if profiles
    (apply deep-merge-with deep-merge
           (for [ms profiles]
             (let [[k v] (first ms)
                   pat ((comp re-pattern name) k)]
               (if (re-find pat job-name)
                 v
                 {}))))
    {}))

(defn load-config [filepath]
  (let [f (io/file filepath)]
    (when (not (.isFile f))
      (throw+ {:error ::file-not-found
               :msg (format "config file %s not found"
                            filepath)}))
    (yaml/parse-string (slurp f))))

(defn load-config-with-profiles
  [job-name filepath]
  (let [conf (load-config filepath)
        res (deep-merge-with deep-merge
                             (dissoc conf :profiles)
                             (merge-profiles job-name (:profiles conf)))]
    res))

(defn system-config []
  (io/file
   (if (windows?)
     "c:\\runbld\\runbld.conf"
     "/etc/runbld/runbld.conf")))

(defn assemble-all-opts
  [{:keys [job-name] :as opts}]
  (deep-merge-with deep-merge
                   config-file-defaults
                   (if (environ/env :dev)
                     {}
                     (let [sys (system-config)]
                       (if (.isFile sys)
                         (load-config-with-profiles job-name (system-config))
                         {})))
                   (if (:configfile opts)
                     (load-config-with-profiles job-name (:configfile opts))
                     {})
                   opts))

(defn normalize
  "Normalize the tools.cli map to the local structure."
  [cli-opts]
  (merge
   {:process (select-keys cli-opts [:program :args :cwd])
    :job-name (:job-name cli-opts)
    :configfile (:config cli-opts)
    :version (:version cli-opts)}
   (when (:java-home cli-opts)
     {:java-home (:java-home cli-opts)})))

(def opts
  [["-v" "--version" "Print version"]
   ["-c" "--config FILE" "Config file"]
   ["-d" "--cwd DIR" "Set CWD for the process"]
   ["-j" "--job-name JOBNAME" (str "Job name: org,project,branch,etc "
                                   "also read from $JOB_NAME")
    :default (environ/env :job-name)]
   [nil "--java-home PATH" "If different from JAVA_HOME or need to override what will be discovered in PATH"]
   ["-p" "--program PROGRAM" "Program that will run the scriptfile"
    :default (if (windows?) "CMD.EXE" "bash")]
   ["-a" "--args ARGS" "Args to pass PROGRAM"
    :default (if (windows?) ["/C"] ["-x"])
    :parse-fn #(str/split % #" ")]
   [nil "--system-info" "Just dump facts output"]
   ["-h" "--help" "Help me"]])

(defn make-script
  ([filename]
   (make-script filename *in*))
  ([filename rdr]
   (if (= filename "-")
     (let [tmp (doto (io/make-tmp-file "stdin" (if (windows?)
                                                 ".bat" ".program"))
                 .deleteOnExit)]
       (spit tmp (slurp rdr))
       (str tmp))
     filename)))

(s/fdef parse-args
        :args (s/cat :argv (s/spec (s/* string?))))
(defn parse-args
  ([args]
   (let [{:keys [options arguments summary errors]
          :as parsed-opts} (cli/parse-opts args opts :nodefault true)]

     (when (:help options)
       (throw+ {:help ::usage
                :msg summary}))

     (when (:system-info options)
       (throw+ {:help ::system}))

     (when (pos? (count errors))
       (throw+ {:error ::parse-error
                :msg (with-out-str
                       (doseq [err errors]
                         (println err)))}))

     (when (:version options)
       (throw+ {:help ::version
                :msg (version/string)}))

     (when (not (= 1 (count arguments)))
       (throw+ {:help ::usage
                :msg (format "runbld %s\nusage: runbld /path/to/script.bash"
                             (version/string))}))

     (when (not (:job-name options))
       (throw+ {:help ::usage
                :msg "must set -j or $JOB_NAME"}))

     (let [options (assemble-all-opts
                    (normalize options))
           java-facts (java/jvm-facts
                       (or
                        (options :java-home)
                        (env/get-env "JAVA_HOME")
                        (-> options :process :env :JAVA_HOME)))
           process-env (merge
                        (when (-> options :process :inherit-env)
                          (env/get-env))
                        (-> options :process :env)
                        {:JAVA_HOME (:home java-facts)})
           scriptfile (make-script (first arguments))]
       (merge (dissoc options :java-home)
              {:es (store/set-up-es! (:es options))
               :env (env/get-env)
               :process (-> (:process options)
                            ;; Invariant: Jenkins passes it in through arguments
                            (assoc :scriptfile scriptfile)
                            ;; Go ahead and resolve
                            (update :cwd io/abspath)
                            (assoc :env process-env))
               :version {:string (version/version)
                         :hash (version/build)}
               :java java-facts})))))

