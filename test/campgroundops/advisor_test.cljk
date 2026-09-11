(ns campgroundops.advisor-test
  "Unit tests of `campgroundops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [campgroundops.advisor :as adv]
            [campgroundops.store :as store]))

(def db (store/seed-db))

(deftest propose-site-occupancy-record-shape
  (testing "site-occupancy-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-site-occupancy-record
                           :site-id "site-1"
                           :patch {:guest "Chen family" :check-in "2026-07-14"}})]
      (is (= :log-site-occupancy-record (:op p)))
      (is (= "site-1" (:site-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :site-id)))))

(deftest propose-facility-maintenance-shape
  (testing "facility-maintenance proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-facility-maintenance
                           :site-id "site-2"
                           :patch {:item "restroom plumbing" :urgency "routine"}})]
      (is (= :schedule-facility-maintenance (:op p)))
      (is (= "site-2" (:site-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-restock-shape
  (testing "supply-restock proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-restock
                           :site-id "site-1"
                           :patch {:item "propane" :quantity 4 :estimated-cost 80}})]
      (is (= :coordinate-supply-restock (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-guest-safety-concern-shape
  (testing "guest-safety-concern proposal always proposes, never actuates"
    (let [p (adv/infer db {:op :flag-guest-safety-concern
                           :site-id "site-1"
                           :patch {:concern "fire risk observed near loop A"}})]
      (is (= :flag-guest-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-site-occupancy-record :schedule-facility-maintenance
                :coordinate-supply-restock :flag-guest-safety-concern]]
      (let [p (adv/infer db {:op op :site-id "site-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-site-occupancy-record :schedule-facility-maintenance
                :coordinate-supply-restock :flag-guest-safety-concern]]
      (let [p (adv/infer db {:op op :site-id "site-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))

(deftest unknown-op-returns-empty-proposal
  (testing "an op outside the four-op set produces an unrecognized (empty) proposal shape, left for the governor to reject"
    (let [p (adv/infer db {:op :not-a-real-op :site-id "site-1" :patch {}})]
      (is (empty? p)))))
