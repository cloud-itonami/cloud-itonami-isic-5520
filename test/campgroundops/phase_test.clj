(ns campgroundops.phase-test
  "Unit tests of `campgroundops.phase` rollout logic."
  (:require [clojure.test :refer [deftest is testing]]
            [campgroundops.phase :as phase]))

(deftest phase-0-read-only
  (testing "phase 0 allows no writes"
    (doseq [op [:log-site-occupancy-record :schedule-facility-maintenance
                :coordinate-supply-restock :flag-guest-safety-concern]]
      (let [{:keys [disposition]} (phase/gate 0 {:op op} :commit)]
        (is (= :hold disposition)
            (str "phase 0 must hold all ops including " op))))))

(deftest phase-1-site-occupancy-record-only
  (testing "phase 1 allows only site-occupancy-record logging, requires approval"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :log-site-occupancy-record} :commit)]
      (is (= :escalate disposition))
      (is (= :phase-approval reason)))
    (let [{:keys [disposition]} (phase/gate 1 {:op :schedule-facility-maintenance} :commit)]
      (is (= :hold disposition)))))

(deftest phase-2-adds-coordination-ops
  (testing "phase 2 allows coordination ops, still requires approval"
    (doseq [op [:log-site-occupancy-record :schedule-facility-maintenance :coordinate-supply-restock]]
      (let [{:keys [disposition]} (phase/gate 2 {:op op} :commit)]
        (is (= :escalate disposition)
            (str "phase 2 op " op " requires approval"))))))

(deftest phase-3-auto-commits-clean-ops
  (testing "phase 3 auto-commits clean, high-conf non-safety ops"
    (let [{:keys [disposition]} (phase/gate 3 {:op :log-site-occupancy-record} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :schedule-facility-maintenance} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-supply-restock} :commit)]
      (is (= :commit disposition)))))

(deftest guest-safety-concern-holds-when-not-enabled
  (testing ":flag-guest-safety-concern holds in phases 0-2 (not yet enabled)"
    (doseq [ph [0 1 2]]
      (let [{:keys [disposition]} (phase/gate ph {:op :flag-guest-safety-concern} :escalate)]
        (is (= :hold disposition)
            (str "phase " ph " has not enabled flag-guest-safety-concern yet"))))))

(deftest guest-safety-concern-escalates-when-enabled
  (testing ":flag-guest-safety-concern ALWAYS escalates when enabled, even if governor says commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :flag-guest-safety-concern} :commit)]
      (is (= :escalate disposition)
          "phase 3 must escalate guest-safety-concerns regardless of governor disposition"))))

(deftest guest-safety-concern-never-in-any-auto-set
  (testing "structural invariant: :flag-guest-safety-concern is never a member of any phase's :auto set"
    (doseq [[ph {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-guest-safety-concern))
          (str "phase " ph " must never auto-commit flag-guest-safety-concern")))))

(deftest hard-hold-always-wins
  (testing "a governor HARD hold stays HOLD regardless of phase"
    (doseq [ph [0 1 2 3]]
      (let [{:keys [disposition]} (phase/gate ph {:op :log-site-occupancy-record} :hold)]
        (is (= :hold disposition)
            (str "phase " ph " must respect governor HARD hold"))))))

(deftest verdict->disposition-maps-correctly
  (testing "verdict->disposition correctly translates governor verdict to base disposition"
    (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
    (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
    (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false})))))
