(ns campgroundops.store
  "SSoT for the ISIC-5520 camping-grounds / RV-park / trailer-park
  OPERATIONS-COORDINATION actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a campground /
  RV park / trailer park: site-occupancy record logging (booking /
  check-in / check-out), restroom / utility-hookup / road facility
  maintenance scheduling, propane / firewood / potable-water supply
  restock coordination, and guest-safety-concern flagging (fire risk,
  wildlife encounter, structural hazard). It NEVER directly executes an
  evacuation order, contacts emergency services, or overrides a
  guest-safety-authority decision -- see `campgroundops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable
  block, per this fleet's Wave 4 person-facing-service safety
  guardrail (ADR-2607152500).

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). A `sites` directory keyed by `:site-id` STRING
  (never a keyword -- consistent keying from the start, avoiding the
  silent-miss bug that plagued an earlier shepherd attempt).

  A registered/verified site-occupancy record must exist before ANY
  proposal for that site may ever commit or escalate --
  `campgroundops.governor`'s `site-unverified-violations` re-derives
  this from the site's own `:registered?`/`:verified?` fields, never
  from a proposal's self-report, the SAME 'ground truth, not
  self-report' discipline every sibling actor's own governor uses.

  The ledger stays append-only: which site a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (site [s site-id] "Registered site-occupancy record, or nil.
    Site map: {:site-id .. :name .. :registered? bool :verified? bool}.")
  (all-sites [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-sites [s sites] "replace/seed the site directory (map site-id->site)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained site directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:sites
   {"site-1" {:site-id "site-1" :name "Pull-through RV site, 50A/30A/20A hookup, loop A"
              :registered? true :verified? true}
    "site-2" {:site-id "site-2" :name "Tent site, water + fire ring, loop B"
              :registered? true :verified? true}
    "site-3" {:site-id "site-3" :name "Trailer site, awaiting utility-hookup inspection, loop C"
              :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ site-id] (get-in @a [:sites site-id]))
  (all-sites [_] (sort-by :site-id (vals (:sites @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s))

(defn seed-db
  "A MemStore seeded with the demo site directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `sites` map (site-id string ->
  site map) -- the primary test/dev entry point. `sites` may be empty
  (an unregistered-everywhere store)."
  [sites]
  (->MemStore (atom {:sites (or sites {}) :ledger [] :coordination-log []})))
