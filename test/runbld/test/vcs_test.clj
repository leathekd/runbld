(ns runbld.test.vcs-test
  (:require [clojure.test :refer :all]
            [schema.test])
  (:require [runbld.vcs :as vcs]
            [runbld.vcs.git :as git]
            :reload-all))

(use-fixtures :once schema.test/validate-schemas)

(deftest test-git-commit
  (git/with-tmp-repo [d "tmp/git/vcs-test"]
    (is (= "Add build"
           (:message
            (vcs/log-latest
             (runbld.vcs.git.GitRepo. d "elastic" "foo" "master")))))))

(deftest test-discovery
  (git/with-tmp-repo [d "tmp/git/vcs-test"]
    (is (= "Add build"
           (:message
            (vcs/log-latest
             (git/make-repo d "elastic" "foo" "master")))))))

