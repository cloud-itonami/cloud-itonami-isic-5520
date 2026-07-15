(ns campgroundops.governor-test
  "Pure unit tests of `campgroundops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [campgroundops.governor :as gov]
            [campgroundops.store :as store]))

(def site-1 {:site-id "site-1" :name "Pull-through RV site" :registered? true :verified? true})
(def site-3 {:site-id "site-3" :name "Trailer site, awaiting inspection" :registered? true :verified? false})

(defn- clean-proposal [op site-id]
  {:op op :site-id site-id :summary "s" :rationale "routine campground operations coordination"
   :cites [site-id] :effect :propose :value {} :confidence 0.85})

(deftest site-unregistered-is-hard
  (testing "no site record at all -> HARD hold"
    (let [s (store/mem-store {"site-1" site-1})
          verdict (gov/check {} nil (clean-proposal :log-site-occupancy-record "unknown-site") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:site-unverified} (map :rule (:violations verdict)))))))

(deftest site-unverified-is-hard
  (testing "site registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"site-3" site-3})
          verdict (gov/check {} nil (clean-proposal :log-site-occupancy-record "site-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:site-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"site-1" site-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-facility-maintenance "site-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"site-1" site-1})
          verdict (gov/check {} nil (clean-proposal :evict-guest "site-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest evacuation-order-execution-is-hard-and-permanent
  (testing "a proposal that claims to directly issue/execute an evacuation order is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"site-1" site-1})
          poisoned (assoc (clean-proposal :flag-guest-safety-concern "site-1")
                          :rationale "issue an evacuation order for loop C immediately"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest contact-emergency-services-content-is-hard
  (testing "a proposal that claims to directly contact emergency services is HARD-blocked, same as evacuation execution"
    (let [s (store/mem-store {"site-1" site-1})
          poisoned (assoc (clean-proposal :flag-guest-safety-concern "site-1")
                          :rationale "contact emergency services directly for site-1 medical situation"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest contact-law-enforcement-content-is-hard
  (testing "a proposal that claims to directly contact law enforcement is HARD-blocked"
    (let [s (store/mem-store {"site-1" site-1})
          poisoned (assoc (clean-proposal :flag-guest-safety-concern "site-1")
                          :summary "call the police about a disturbance at site-1")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest override-guest-safety-authority-content-is-hard
  (testing "a proposal that claims to override a guest-safety-authority decision is HARD-blocked"
    (let [s (store/mem-store {"site-1" site-1})
          poisoned (assoc (clean-proposal :schedule-facility-maintenance "site-1")
                          :value {:decision "override guest safety authority closure order for loop A"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-guest-safety-concern-is-not-scope-excluded
  (testing "flagging observed fire-risk/wildlife/structural concerns (including a mention of a blocked evacuation route as raw observation, not an executed action) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"site-1" site-1})
          concern (assoc (clean-proposal :flag-guest-safety-concern "site-1")
                         :value {:concern "fire risk observed near site-12; a fallen tree partially blocks the evacuation route near loop A"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (fire risk, blocked route) is exactly what this op exists to surface"))))

(deftest guest-safety-concern-always-escalates-even-when-otherwise-clean
  (testing ":flag-guest-safety-concern is always high-stakes/escalate, regardless of confidence"
    (let [s (store/mem-store {"site-1" site-1})
          concern (assoc (clean-proposal :flag-guest-safety-concern "site-1") :confidence 0.99)
          verdict (gov/check {} nil concern s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-supply-restock-always-escalates
  (testing "a coordinate-supply-restock proposal above the cost threshold escalates even when governor-clean and high confidence"
    (let [s (store/mem-store {"site-1" site-1})
          expensive (assoc (clean-proposal :coordinate-supply-restock "site-1")
                           :value {:estimated-cost 900} :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-supply-restock-does-not-force-escalation
  (testing "a coordinate-supply-restock proposal under the cost threshold is not forced to escalate on cost grounds alone"
    (let [s (store/mem-store {"site-1" site-1})
          routine (assoc (clean-proposal :coordinate-supply-restock "site-1")
                        :value {:estimated-cost 80} :confidence 0.9)
          verdict (gov/check {} nil routine s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict))))))

(deftest low-confidence-escalates
  (testing "confidence below the floor escalates any otherwise-clean proposal"
    (let [s (store/mem-store {"site-1" site-1})
          uncertain (assoc (clean-proposal :log-site-occupancy-record "site-1") :confidence 0.4)
          verdict (gov/check {} nil uncertain s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest clean-high-confidence-proposal-is-ok
  (testing "a clean, high-confidence, low-cost, registered-site proposal is fully ok"
    (let [s (store/mem-store {"site-1" site-1})
          clean (clean-proposal :log-site-occupancy-record "site-1")
          verdict (gov/check {} nil clean s)]
      (is (true? (:ok? verdict)))
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict))))))
