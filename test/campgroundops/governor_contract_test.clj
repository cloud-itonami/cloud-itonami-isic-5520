(ns campgroundops.governor-contract-test
  "Integration tests: full OperationActor graph exercising the governor's
  hard checks, escalation logic, and audit trail."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [campgroundops.advisor :as advisor]
            [campgroundops.store :as store]
            [campgroundops.operation :as op]))

(defn exec-request [actor tid request ctx]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn resume-approval [actor tid status]
  (g/run* actor {:approval {:status status :by "manager"}} {:thread-id tid :resume? true}))

(deftest site-occupancy-record-full-flow
  (testing "clean site-occupancy-record proposal -> auto-commit at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-1" :phase 3}
          result (exec-request actor "t1"
                               {:op :log-site-occupancy-record :site-id "site-1" :patch {:guest "Chen family" :check-in "2026-07-14"}}
                               ctx)]
      (is (some? result))
      (is (> (count (store/ledger db)) 0)
          "commit must append audit facts to ledger")
      (is (> (count (store/coordination-log db)) 0)
          "commit must append record to coordination-log"))))

(deftest guest-safety-concern-always-escalates
  (testing ":flag-guest-safety-concern escalates for human approval, regardless of phase/confidence"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2" :phase 3}
          result (exec-request actor "t2"
                               {:op :flag-guest-safety-concern :site-id "site-1"
                                :patch {:concern "fire risk" :confidence 0.99}}
                               ctx)]
      (is (some? result))
      ;; At this point the actor is paused for approval, not yet committed
      (is (= 0 (count (store/coordination-log db)))
          "safety concern must not auto-commit, must wait for approval")
      ;; Now approve it
      (resume-approval actor "t2" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, record must be committed"))))

(deftest high-cost-supply-restock-escalates-then-commits-on-approval
  (testing "an over-threshold supply-restock proposal escalates, then commits once a human approves"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2b" :phase 3}
          result (exec-request actor "t2b"
                               {:op :coordinate-supply-restock :site-id "site-1"
                                :patch {:item "bulk firewood pallet order" :estimated-cost 900}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "over-threshold restock must not auto-commit")
      (resume-approval actor "t2b" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, record must be committed"))))

(deftest unregistered-site-hard-hold
  (testing "unregistered site -> permanent HARD hold, never escalates"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-3" :phase 3}]
      (exec-request actor "t3"
                     {:op :log-site-occupancy-record :site-id "unknown-site"
                      :patch {:guest "unknown"}}
                     ctx)
      (is (= 0 (count (store/coordination-log db)))
          "HARD hold must never commit"))))

(deftest unverified-site-hard-hold
  (testing "registered but unverified site -> permanent HARD hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-4" :phase 3}
          result (exec-request actor "t4"
                               {:op :log-site-occupancy-record :site-id "site-3"
                                :patch {:guest "unknown"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "unverified site must HARD hold"))))

(deftest effect-not-propose-hard-hold
  (testing "proposal with :effect :commit (not :propose) -> hard hold"
    (let [db (store/seed-db)
          bad-advisor (reify advisor/Advisor
                        (-advise [_ _ req]
                          (assoc (advisor/infer nil req) :effect :commit)))
          actor (op/build db {:advisor bad-advisor})
          ctx {:actor-id "test-5" :phase 3}
          result (exec-request actor "t5"
                               {:op :log-site-occupancy-record :site-id "site-1"
                                :patch {:guest "Chen family"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "non-:propose effect must HARD hold"))))

(deftest scope-excluded-content-hard-hold
  (testing "proposal drifting into evacuation-order-execution/emergency-services-contact scope -> permanent hard hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-6" :phase 3}
          result (exec-request actor "t6"
                               {:op :log-site-occupancy-record :site-id "site-1"
                                :out-of-scope? true  ; triggers scope pollution in advisor
                                :patch {}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/coordination-log db)))
          "scope-excluded content must HARD hold"))))

(deftest phase-1-approval-gate
  (testing "phase 1 approved request -> commits after human approval"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-7" :phase 1}]
      (exec-request actor "t7"
                     {:op :log-site-occupancy-record :site-id "site-1"
                      :patch {:guest "Chen family"}}
                     ctx)
      (is (= 0 (count (store/coordination-log db)))
          "phase 1 must not auto-commit, requires approval")
      (resume-approval actor "t7" :approved)
      (is (> (count (store/coordination-log db)) 0)
          "after approval, must commit")
      (is (some #(= :committed (:t %)) (store/ledger db))
          "committed fact must be logged after approval"))))

(deftest audit-trail-completeness
  (testing "every decision leaves immutable audit facts"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-8" :phase 3}]
      (exec-request actor "t8a"
                     {:op :log-site-occupancy-record :site-id "site-1" :patch {:guest "Chen family"}}
                     ctx)
      (exec-request actor "t8b"
                     {:op :log-site-occupancy-record :site-id "unknown" :patch {:guest "Chen family"}}
                     ctx)
      (let [ledger (store/ledger db)]
        (is (> (count ledger) 0))
        (is (some #(= :committed (:t %)) ledger)
            "successful commits must be logged")
        (is (some #(= :governor-hold (:t %)) ledger)
            "HARD holds must be logged")))))

(deftest approval-rejection-holds-not-commits
  (testing "an approver rejecting an escalated proposal results in HOLD, never commit"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-9" :phase 3}]
      (exec-request actor "t9"
                     {:op :flag-guest-safety-concern :site-id "site-1"
                      :patch {:concern "unattended campfire"}}
                     ctx)
      (resume-approval actor "t9" :rejected)
      (is (= 0 (count (store/coordination-log db)))
          "a rejected approval must never commit")
      (is (some #(= :approval-rejected (:t %)) (store/ledger db))
          "rejection must be logged"))))
