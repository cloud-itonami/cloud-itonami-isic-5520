(ns campgroundops.phase
  "Phase 0->3 staged rollout for the ISIC-5520 camping-grounds / RV-park
  / trailer-park operations-coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-logging     -- site-occupancy record logging
                                      allowed, every write needs human
                                      approval.
    Phase 2  assisted-coordination-- adds facility maintenance
                                      scheduling and supply-restock
                                      coordination, still approval.
    Phase 3  supervised auto      -- governor-clean, high-confidence
                                      `:log-site-occupancy-record`/
                                      `:schedule-facility-maintenance`/
                                      `:coordinate-supply-restock` may
                                      auto-commit. `:flag-guest-safety-concern`
                                      NEVER auto-commits, at any phase.

  `:flag-guest-safety-concern` is deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come (Wave 4
  person-facing-service safety guardrail, ADR-2607152500). Flagging a
  safety concern always needs a human to actually look at it.
  `campgroundops.governor`'s own `always-escalate-ops` enforces the
  same invariant independently -- two layers, not one, agree on this."
  (:require [campgroundops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-guest-safety-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member
;; of any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops
  allowed to auto-commit when governor-clean>}."
  {0 {:label "read-only"              :writes #{}                                                                :auto #{}}
   1 {:label "assisted-logging"       :writes #{:log-site-occupancy-record}                                      :auto #{}}
   2 {:label "assisted-coordination"  :writes #{:log-site-occupancy-record :schedule-facility-maintenance
                                                :coordinate-supply-restock}                                      :auto #{}}
   3 {:label "supervised-auto"        :writes write-ops
      :auto #{:log-site-occupancy-record :schedule-facility-maintenance :coordinate-supply-restock}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean.
  - `:flag-guest-safety-concern` is never auto-eligible at any phase,
    so it always escalates once the governor clears it (or holds if
    the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a CampgroundGovernor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
