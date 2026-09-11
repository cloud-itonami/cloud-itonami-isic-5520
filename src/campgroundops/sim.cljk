(ns campgroundops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean site-occupancy-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then a facility-maintenance-scheduling
  request and a supply-restock coordination request (both auto-commit
  clean at phase 3), then a guest-safety-concern flag (ALWAYS
  escalates, at any phase -- approve, then commit), then HARD-hold
  scenarios: an unregistered site, a site registered but not yet
  verified, a proposal whose own `:effect` is not `:propose`, and a
  proposal that has drifted into the permanently-excluded
  evacuation-order-execution / emergency-services-contact scope."
  (:require [langgraph.graph :as g]
            [campgroundops.advisor :as advisor]
            [campgroundops.store :as store]
            [campgroundops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "campground-manager-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        manager-phase-1 {:actor-id "mgr-1" :actor-role :campground-manager :phase 1}
        manager-phase-3 {:actor-id "mgr-1" :actor-role :campground-manager :phase 3}
        actor (op/build db)]

    (println "== log-site-occupancy-record site-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-site-occupancy-record :site-id "site-1"
                                  :patch {:guest "Chen family" :check-in "2026-07-14" :nights 3}} manager-phase-1)]
      (println r)
      (println "-- human campground manager approves --")
      (println (approve! actor "t1")))

    (println "\n== log-site-occupancy-record site-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-site-occupancy-record :site-id "site-1"
                                  :patch {:guest "Chen family" :check-out "2026-07-17"}} manager-phase-3))

    (println "\n== schedule-facility-maintenance site-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-facility-maintenance :site-id "site-1"
                                  :patch {:item "50A hookup breaker inspection" :urgency "routine"}} manager-phase-3))

    (println "\n== coordinate-supply-restock site-1 (phase 3, clean, under threshold -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-restock :site-id "site-1"
                                  :patch {:item "propane" :quantity 4 :estimated-cost 80}} manager-phase-3))

    (println "\n== coordinate-supply-restock site-1 (phase 3, over cost threshold -- ALWAYS escalates) ==")
    (let [r (exec-op actor "t4b" {:op :coordinate-supply-restock :site-id "site-1"
                                  :patch {:item "bulk firewood pallet order" :quantity 200 :estimated-cost 900}} manager-phase-3)]
      (println r)
      (println "-- human campground manager approves --")
      (println (approve! actor "t4b")))

    (println "\n== flag-guest-safety-concern site-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-guest-safety-concern :site-id "site-1"
                                 :patch {:concern "smoldering fire ring reported unattended near loop A" :confidence 0.92}} manager-phase-3)]
      (println r)
      (println "-- human campground manager reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-site-occupancy-record site-99 (unregistered site -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-site-occupancy-record :site-id "site-99"
                                  :patch {:guest "unknown"}} manager-phase-3))

    (println "\n== log-site-occupancy-record site-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-site-occupancy-record :site-id "site-3"
                                  :patch {:guest "unknown"}} manager-phase-3))

    (println "\n== schedule-facility-maintenance site-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-facility-maintenance :site-id "site-1"
                                           :patch {:item "road grading"}} manager-phase-3)))

    (println "\n== log-site-occupancy-record site-1, advisor drifts into evacuation/emergency-services scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-site-occupancy-record :site-id "site-1"
                                   :out-of-scope? true
                                   :patch {}} manager-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
