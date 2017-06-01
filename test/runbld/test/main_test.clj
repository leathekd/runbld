(ns runbld.test.main-test
  (:require [cheshire.core :as json]
            [clj-git.core :refer [git-branch git-clone git-log load-repo]]
            [clj-http.core :as http-core]
            [clojure.java.io :as jio]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [runbld.build :as build]
            [runbld.env :as env]
            [runbld.io :as io]
            [runbld.notifications.email :as email]
            [runbld.notifications.slack :as slack]
            [runbld.opts :as opts]
            [runbld.process :as proc]
            [runbld.scheduler :as scheduler]
            [runbld.scheduler.default :as default-sched]
            [runbld.store :as store]
            [runbld.test.support :as ts]
            [runbld.util.http :refer [wrap-retries]]
            [runbld.vcs.git :as git]
            [runbld.version :as version]
            [schema.test :as s]
            [stencil.core :as mustache])
  (:require [runbld.main :as main] :reload-all))

(def email (atom []))
(def slack (atom []))

(defn run [args]
  [(opts/parse-args args) (apply main/-main args)])

(defn slack-msg []
  (-> @slack
      json/decode
      keywordize-keys
      :attachments
      first
      :title))

(use-fixtures :each ts/redirect-logging-fixture)

(s/deftest main
  ;; Change root bindings for these Vars, affects any execution no
  ;; matter what thread
  (with-redefs [;; Don't really kill the JVM
                main/really-die (fn [& args] :dontdie)
                ;; Don't really execute an external process
                proc/run (fn [& args] :bogus)]
    (testing "version"
      (is (.startsWith (main/-main "-v") (version/version))))

    (testing "usage"
      (is (.startsWith (main/-main) "runbld ")))

    (testing "bad config file"
      (is (.startsWith (main/-main "-c" "/tmp/noexist"
                                   "-j" "elastic+foo+master"
                                   "/path/to/script.bash") "config file ")))))

(s/deftest unexpected
  (with-redefs [main/really-die (fn [& args] :dontdie)
                proc/run (fn [& args] (throw
                                       (Exception.
                                        "boy that was unexpected")))]
    (git/with-tmp-repo [d "tmp/git/main-test-1"]
      (let [[opts res] (run ["-c" "test/config/main.yml"
                             "-j" "elastic+foo+master"
                             "-d" d
                             "/path/to/script.bash"])]
        (is (= String (type res)))
        (is (.startsWith res "#error {\n :cause boy that was "))))))

(s/deftest execution-with-defaults
  (testing "real execution all the way through"
    (with-redefs [email/send* (fn [& args]
                                (swap! email concat args)
                                ;; to satisfy schema
                                {})
                  slack/send (fn [opts ctx]
                               (let [f (-> opts :slack :template)
                                     tmpl (-> f io/resolve-resource slurp)
                                     color (if (-> ctx :process :failed)
                                             "danger"
                                             "good")
                                     js (mustache/render-string
                                         tmpl (assoc ctx :color color))]
                                 (reset! slack js)))]
      (testing "build failure -- default notification settings"
        (git/with-tmp-repo [d "tmp/git/main-test-2"]
          (let [[opts res] (run
                             (conj
                              ["-c" "test/config/main.yml"
                               "-j" "elastic+foo+master"
                               "-d" d]
                              (if (opts/windows?)
                                "test/fail.bat"
                                "test/fail.bash")))]
            (is (= 1 (:exit-code res)))
            (is (= 1 (-> (store/get (-> opts :es :conn)
                                    (-> res :store-result :addr :index)
                                    (-> res :store-result :addr :type)
                                    (-> res :store-result :addr :id))
                         :process
                         :exit-code)))
            (is (.startsWith
                 (let [[_ _ _ subj _ _] @email] subj) "FAILURE"))
            (is (.contains (slack-msg) "FAILURE")))))
      (testing "build success: default notification settings"
        (reset! email [])
        (reset! slack [])
        (git/with-tmp-repo [d "tmp/git/main-test-3"]
          (let [[opts res] (run
                             (conj
                              ["-c" "test/config/main.yml"
                               "-j" "elastic+foo+master"
                               "-d" d]
                              "test/success.bash"))]
            (is (= 0 (:exit-code res)))
            (is (= 0 (-> (store/get (-> opts :es :conn)
                                    (-> res :store-result :addr :index)
                                    (-> res :store-result :addr :type)
                                    (-> res :store-result :addr :id))
                         :process
                         :exit-code)))
            (is (empty? @email))
            (is (.contains (slack-msg) "SUCCESS"))))))))

(s/deftest execution-with-slack-overrides
  (testing "slack overrides:"
    (with-redefs [email/send* (fn [& args]
                                (swap! email concat args)
                                ;; to satisfy schema
                                {})
                  slack/send (fn [opts ctx]
                               (let [f (-> opts :slack :template)
                                     tmpl (-> f io/resolve-resource slurp)
                                     color (if (-> ctx :process :failed)
                                             "danger"
                                             "good")
                                     js (mustache/render-string
                                         tmpl (assoc ctx :color color))]
                                 (reset! slack js)))]
      (testing "build success: notify on first success after failure(s)"
        (reset! email [])
        (reset! slack [])
        ;; fail first
        (git/with-tmp-repo [d "tmp/git/main-test-4"]
          (let [[opts res] (run (conj
                                 ["-c" "test/config/slack.yml"
                                  "-j" "elastic+foo+master"
                                  "-d" d]
                                 "test/fail.bash"))]))
        ;; then succeed
        (reset! email [])
        (reset! slack [])
        (git/with-tmp-repo [d "tmp/git/main-test-5"]
          (let [args (conj
                      ["-c" "test/config/slack.yml"
                       "-j" "elastic+foo+master"
                       "-d" d]
                      "test/success.bash")
                opts (merge (opts/parse-args args)
                            {:slack
                             {:success false}})
                res (apply main/-main args)]
            (is (empty? @email))
            ;; we should get a slack notification
            (is (.contains (slack-msg) "SUCCESS")))))
      (testing "build success: don't notify on subsequent successes"
        ;; succeed once more
        (reset! email [])
        (reset! slack [])
        (git/with-tmp-repo [d "tmp/git/main-test-6"]
          (let [[opts res] (run (conj
                                 ["-c" "test/config/slack.yml"
                                  "-j" "elastic+foo+master"
                                  "-d" d]
                                 "test/success.bash"))]
            ;; we shouldn't get any more notifications
            (is (empty? @email))
            (is (empty? @slack))))))))

(s/deftest last-good-commit
  (testing "last-good-commit:"
    (testing "successful intake"
      (let [email-body (atom :no-email-body-yet)]
        (with-redefs [email/send* (fn [_ _ _ _ plain html attachments]
                                    (reset! email-body html)
                                    ;; to satisfy schema
                                    {})
                      slack/send (fn [& _] (prn 'slack))]
          (let [intake-dir "tmp/git/main-intake"
                periodic-dir "tmp/git/main-periodic"]
            (try
              (git/init-test-clone periodic-dir intake-dir)
              (let [[opts-intake res-intake]
                    (run (conj
                          ["-c" "test/config/main.yml"
                           "-j" "elastic+foo+master+intake"
                           "-d" intake-dir]
                          (if (opts/windows?)
                            "test/success.bat"
                            "test/success.bash")))]
                (let [[opts-periodic res-periodic]
                      (run (conj
                            ["-c" "test/config/main.yml"
                             "-j" "elastic+foo+master+periodic"
                             "-d" periodic-dir
                             "--last-good-commit" "elastic+foo+master+intake"]
                            (if (opts/windows?)
                              "test/fail.bat"
                              "test/fail.bash")))]
                  (is (= 0 (:exit-code res-intake)))
                  (is (= 1 (:exit-code res-periodic)))
                  (is (.contains @email-body "using successful commit")
                      (with-out-str
                        (println "@email-body")
                        (prn @email-body)))))
              (finally
                (io/rmdir-r periodic-dir)
                (io/rmdir-r intake-dir)))))))))

(s/deftest set-scm-build
  (testing "the scm build can be set multiple ways:"
    (let [args (atom [])]
      (with-redefs [git-clone (fn [_ _ clone-args]
                                (reset! args clone-args))
                    main/wipe-workspace (fn [workspace] nil)
                    scheduler/as-map identity]
        (testing "supplying branch in yml"
          (git/with-tmp-repo [d "tmp/git/owner+project+branch"]
            (let [raw-opts (-> (opts/parse-args
                                ["-c" "test/config/scm.yml"
                                 "-j" "owner+project+master"
                                 "-d" "tmp/git/owner+project+branch"
                                 "test/fail.bash"])
                               (assoc :logger runbld.io/log)
                               ;; make schema happy
                               (assoc :scheduler (default-sched/make {})))
                  opts (build/add-build-meta raw-opts)]
              (main/bootstrap-workspace
               (assoc-in opts [:scm :wipe-workspace] false))
              (is (= "master"
                     (-> opts :scm :branch)
                     (-> opts :build :branch)
                     (get (apply hash-map @args) "--branch"))))))
        (testing "extracting branch from job"
          (git/with-tmp-repo [d "tmp/git/owner+project+branch"]
            (let [raw-opts (-> (conj
                                ["-c" "test/config/scm.yml"
                                 ;; NOTE: the job name changed
                                 "-j" "owner+project+branch"
                                 "-d" "tmp/git/owner+project+branch"]
                                "test/fail.bash")
                               opts/parse-args
                               (assoc :logger runbld.io/log)
                               ;; make schema happy
                               (assoc :scheduler (default-sched/make {})))
                  opts (build/add-build-meta raw-opts)]
              (main/bootstrap-workspace
               (assoc-in opts [:scm :wipe-workspace] false))
              (is (nil? (-> opts :scm :branch)))
              (is (= "branch"
                     (-> opts :build :branch)
                     (get (apply hash-map @args) "--branch"))))))))))

(s/deftest ^:integration execution-with-scm
  (testing "real execution all the way through with cloning via scm config"
    (let [wipe-workspace-orig main/wipe-workspace
          workspace "tmp/git/elastic+foo+master"]
      (with-redefs [email/send* (fn [& args]
                                  (swap! email concat args)
                                  ;; to satisfy schema
                                  {})
                    slack/send (fn [opts ctx]
                                 (let [f (-> opts :slack :template)
                                       tmpl (-> f io/resolve-resource slurp)
                                       color (if (-> ctx :process :failed)
                                               "danger"
                                               "good")
                                       js (mustache/render-string
                                           tmpl (assoc ctx :color color))]
                                   (reset! slack js)))
                    main/wipe-workspace (fn [opts] opts)
                    main/find-workspace (constantly workspace)]
        (try
          (let [[opts res] (run
                             (conj
                              ["-c" "test/config/scm.yml"
                               "-j" "elastic+foo+master"
                               "-d" workspace]
                              (if (opts/windows?)
                                "test/fail.bat"
                                "test/fail.bash")))]
            (testing "the elasticsearch repo was cloned correctly"
              (let [branch (-> opts :scm :branch)
                    depth (-> opts :scm :depth)
                    repo (load-repo workspace)]
                (is (= branch (git-branch repo)))
                (is (= depth (count (git-log repo)))))
              (testing "scm doesn't break anything else"
                (is (= 1 (:exit-code res)))
                (is (= 1 (-> (store/get (-> opts :es :conn)
                                        (-> res :store-result :addr :index)
                                        (-> res :store-result :addr :type)
                                        (-> res :store-result :addr :id))
                             :process
                             :exit-code)))
                (is (.startsWith
                     (let [[_ _ _ subj _ _] @email] subj) "FAILURE"))
                (is (.contains (slack-msg) "FAILURE")))
              (testing "wiping the workspace"
                (wipe-workspace-orig opts)
                ;; Should only have the top-level workspace dir left
                (is (= [workspace]
                       (map str (file-seq (jio/file workspace))))))))
          (finally
            (io/rmdir-r workspace)))))))

(deftest http-retries
  (let [call-count (atom 0)]
    (with-redefs [http-core/http-client (fn [& _]
                                          (swap! call-count inc)
                                          (throw (Exception. "oh noes!")))
                  email/send* (fn [& args]
                                (swap! email concat args)
                                ;; to satisfy schema
                                {})
                  slack/send (fn [opts ctx]
                               (let [f (-> opts :slack :template)
                                     tmpl (-> f io/resolve-resource slurp)
                                     color (if (-> ctx :process :failed)
                                             "danger"
                                             "good")
                                     js (mustache/render-string
                                         tmpl (assoc ctx :color color))]
                                 (reset! slack js)))]
      (testing "The live path will retry correctly"
        (is (thrown-with-msg? Exception #"oh noes"
                              (git/with-tmp-repo [d "tmp/git/main-test-3"]
                                ;; this call to run will fail b/c of
                                ;; the redefined http-client call
                                ;; above
                                (run (conj ["-c" "test/config/main.yml"
                                            "-j" "elastic+foo+master"
                                            "-d" d]
                                           "test/success.bash")))))
        (is (= 20 @call-count)
            "Should've retried the default number of times"))
      (testing "Can configure the number of retries"
        (reset! call-count 0)
        (let [settings (merge
                        (:es opts/config-file-defaults)
                        {:url (str "http://" (java.util.UUID/randomUUID) ".io")
                         :additional-middleware [wrap-retries]
                         :retries-config {:sleep 50
                                          :tries 5}})
              conn (store/make-connection settings)]
          (is (thrown-with-msg? Exception #"oh noes"
                                (store/get conn "index" "type" 1234)))
          (is (= 5 @call-count))))
      (testing "Can configure the retried exceptions"
        (reset! call-count 0)
        (let [settings (merge
                        (:es opts/config-file-defaults)
                        {:url (str "http://" (java.util.UUID/randomUUID) ".io")
                         :additional-middleware [wrap-retries]
                         :retries-config {:sleep 50
                                          :tries 5}})
              conn (store/make-connection settings)]
          (is (thrown-with-msg? Exception #"oh noes"
                                (store/get conn "index" "type" 1234)))
          (is (= 5 @call-count)))))))

(deftest record-progress
  (let [ready (promise)
        proceed (promise)
        done (promise)
        res (atom nil)]
    (with-redefs [main/send-email identity
                  main/send-slack (fn [opts]
                                    (deliver done true)
                                    opts)
                  main/wipe-workspace (fn [opts]
                                        (reset! res opts)
                                        (deliver ready true)
                                        (is (deref proceed 6000 nil)
                                            "Timed out waiting to proceed")
                                        opts)]
      (git/with-tmp-repo [d "tmp/git/record-progress-test-1"]
        (let [args (conj
                    ["-c" "test/config/main.yml"
                     "-j" "elastic+foo+master"
                     "-d" d]
                    "test/success.bash")
              opts (opts/parse-args args)]
          (future (apply main/-main args))
          (when (is (deref ready 4000 nil)
                    "Timed out waiting on pipeline")
            (testing "we've started, we should have a record"
              (let [record (store/get (-> opts :es :conn)
                                      (-> @res :store-result :addr :index)
                                      (-> @res :store-result :addr :type)
                                      (-> @res :store-result :addr :id))]
                (is (= "elastic" (-> record :build :org)))
                (is (nil? (-> record :process :exit-code)))))
            (deliver proceed true)
            (when (is (deref done 10000 nil)
                      "Timed out waiting on pipeline to finish")
              (let [record (store/get (-> opts :es :conn)
                                      (-> @res :store-result :addr :index)
                                      (-> @res :store-result :addr :type)
                                      (-> @res :store-result :addr :id))]
                (is (= "elastic" (-> record :build :org)))
                (is (zero? (-> record :process :exit-code)))))))))))
