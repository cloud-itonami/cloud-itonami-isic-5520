(ns campgroundops.store-contract-test
  "Contract tests for `campgroundops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [campgroundops.store :as store]))

(deftest mem-store-site-lookup
  (testing "MemStore can store and retrieve sites by ID (string keys)"
    (let [sites {"s1" {:site-id "s1" :name "Site 1" :registered? true :verified? true}}
          s (store/mem-store sites)]
      (is (some? (store/site s "s1")))
      (is (nil? (store/site s "s99"))))))

(deftest mem-store-all-sites
  (testing "MemStore returns all sites in sorted order"
    (let [sites {"s2" {:site-id "s2" :name "Site 2"}
                 "s1" {:site-id "s1" :name "Site 1"}
                 "s3" {:site-id "s3" :name "Site 3"}}
          s (store/mem-store sites)
          all-s (store/all-sites s)]
      (is (= 3 (count all-s)))
      (is (= "s1" (:site-id (first all-s))))
      (is (= "s3" (:site-id (last all-s)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-site-occupancy-record :site-id "s1" :value {:guest "test"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-sites
  (testing "MemStore with-sites replaces the site directory"
    (let [s (store/mem-store {})
          new-sites {"s1" {:site-id "s1" :name "Site 1"}}]
      (is (= 0 (count (store/all-sites s))))
      (store/with-sites s new-sites)
      (is (= 1 (count (store/all-sites s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo sites"
    (let [s (store/seed-db)]
      (is (> (count (store/all-sites s)) 0))
      (is (some? (store/site s "site-1")))
      (is (some? (store/site s "site-2")))
      (is (some? (store/site s "site-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for site-id"
    (let [demo (store/demo-data)
          sites (:sites demo)]
      (doseq [[k v] sites]
        (is (string? k) "keys must be strings")
        (is (string? (:site-id v)) "site-id must be string")
        (is (= k (:site-id v)) "key must match site-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
