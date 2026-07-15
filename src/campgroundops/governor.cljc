(ns campgroundops.governor
  "CampgroundGovernor -- the independent compliance layer that earns
  the CampgroundOpsAdvisor the right to commit. The advisor has no
  notion of whether a site-occupancy record is actually registered and
  verified, whether its own proposed `:effect` secretly claims a
  direct actuation instead of a mere proposal, or whether it has
  silently drifted into a permanently out-of-scope decision area, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (site-occupancy record logging, facility maintenance scheduling,
  supply-restock coordination, guest-safety-concern flagging). It
  NEVER performs or authorizes:
    - directly issuing/executing an evacuation order
    - directly contacting emergency medical services or law enforcement
    - overriding a guest-safety-authority decision
    - any other guest-safety-authority action

  This is the Wave 4 person-facing-service safety guardrail
  (ADR-2607152500): even though this actor is not a medical/care
  setting, the same discipline applies proportionally -- the closed op
  allowlist NEVER includes any op that directly executes a
  guest-safety-authority decision (evacuation orders, emergency
  medical response, law-enforcement contact); those are always either
  a hard permanent block or an always-escalate op, never
  auto-commit-eligible.

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Site unverified            -- the target site-occupancy record
                                      must exist AND be independently
                                      confirmed `:registered?`/
                                      `:verified?` in the store before
                                      ANY proposal for it may commit or
                                      even escalate. Never trusts a
                                      proposal's own claim about the
                                      site -- re-derived from the
                                      site's own store record, the same
                                      'ground truth, not self-report'
                                      discipline every sibling actor's
                                      governor uses.
    2. Effect not :propose        -- every proposal's `:effect` MUST
                                      be `:propose`. Any other effect
                                      value is, by construction, a
                                      claim to directly actuate/commit
                                      outside governance -- HARD block,
                                      not merely low-confidence.
    3. Scope exclusion            -- ANY proposal (regardless of op)
                                      whose op is outside the closed
                                      four-op allowlist, or whose
                                      rationale, summary, citations or
                                      draft value touches directly
                                      issuing/executing an evacuation
                                      order, directly contacting
                                      emergency medical services or law
                                      enforcement, or overriding a
                                      guest-safety-authority decision,
                                      is a HARD, PERMANENT block -- this
                                      actor's charter excludes that
                                      territory structurally, not as a
                                      rollout milestone. Evaluated
                                      UNCONDITIONALLY on every
                                      proposal. An op outside the
                                      closed four-op allowlist is the
                                      SAME failure mode (an advisor
                                      proposing something it was never
                                      authorized to propose) and is
                                      folded into this same check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-guest-safety-concern` (ALWAYS escalates to a human,
  regardless of confidence, regardless of how clean the proposal
  otherwise is), OR a `:coordinate-supply-restock` proposal whose
  estimated cost exceeds `high-cost-threshold`.
  `campgroundops.phase` independently agrees: `:flag-guest-safety-concern`
  is never a member of any phase's `:auto` set either -- two layers,
  not one."
  (:require [clojure.string :as str]
            [campgroundops.store :as store]))

(def confidence-floor 0.6)

(def high-cost-threshold
  "A `:coordinate-supply-restock` proposal whose `:value
  :estimated-cost` exceeds this amount (USD) ALWAYS escalates to a
  human, regardless of confidence -- routine propane/firewood/
  potable-water restocks sit well under this, so this only catches
  unusually large orders."
  500)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`).
  Per the Wave 4 person-facing-service safety guardrail
  (ADR-2607152500), NO op in this set may directly execute a
  guest-safety-authority decision (evacuation orders, emergency
  medical response, law-enforcement contact) -- every op here is
  `:effect :propose` only, and `:flag-guest-safety-concern` always
  escalates rather than ever auto-committing."
  #{:log-site-occupancy-record :schedule-facility-maintenance
    :coordinate-supply-restock :flag-guest-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-guest-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly issuing/executing
  an evacuation order, directly contacting emergency medical services
  or law enforcement, or overriding a guest-safety-authority decision.
  Scanned across the proposal's op/summary/rationale/cites/value,
  never trusting the advisor's own framing of its intent.

  Deliberately phrased as EXECUTION/ACTUATION phrases (verb + object),
  not bare nouns like \"evacuat\" alone -- a legitimate
  `:flag-guest-safety-concern` proposal must be free to *describe* a
  fire risk, a blocked evacuation route, or a wildlife encounter
  without tripping this gate (see `campgroundops.governor-test`'s own
  `legitimate-guest-safety-concern-is-not-scope-excluded`); only a
  proposal that claims to *actually perform* the guest-safety-authority
  action is blocked here."
  ["issue an evacuation order" "issue evacuation order" "execute evacuation" "order evacuation"
   "direct evacuation" "initiate evacuation" "declare evacuation" "carry out evacuation"
   "call 911" "dial 911" "contact emergency services" "contact emergency medical services"
   "dispatch emergency medical" "dispatch ems" "provide emergency medical" "administer emergency medical"
   "perform emergency medical" "contact law enforcement" "contact the police" "call the police"
   "call law enforcement" "dispatch police" "dispatch law enforcement"
   "override guest safety authority" "override the guest safety authority" "override safety authority"
   "override the safety authority" "bypass guest safety authority" "bypass safety authority"
   "避難命令を発令" "避難命令を出" "緊急医療を実施" "緊急医療を提供" "警察に通報" "警察へ通報"
   "消防に通報" "消防へ通報" "安全当局の判断を覆" "安全当局の決定を無視"])

;; ----------------------------- checks -----------------------------

(defn- site-unverified-violations
  "The target site-occupancy record must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:site-id` claim without a store lookup."
  [{:keys [site-id]} st]
  (let [r (store/site st site-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :site-unverified
        :detail (str site-id " は未登録または未検証のサイト -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches directly issuing/executing an
  evacuation order, directly contacting emergency medical services or
  law enforcement, or overriding a guest-safety-authority decision,
  regardless of confidence or how clean every other check is.
  Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "避難命令の発令/緊急当局への直接連絡/ゲスト安全当局の判断の上書きは永久に禁止"}])))

(defn- high-cost-supply-restock?
  "A `:coordinate-supply-restock` proposal whose `:value
  :estimated-cost` exceeds `high-cost-threshold` ALWAYS escalates,
  regardless of confidence."
  [proposal]
  (and (= :coordinate-supply-restock (:op proposal))
       (some-> (get-in proposal [:value :estimated-cost])
               (> high-cost-threshold))))

(defn check
  "Censors a CampgroundOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [site-id (or (:site-id proposal) (:site-id request))
        hard (into []
                   (concat (site-unverified-violations {:site-id site-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-restock? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :site-id    (:site-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
