(ns campgroundops.advisor
  "CampgroundOpsAdvisor -- the *contained intelligence node* for the
  ISIC-5520 camping-grounds / RV-park / trailer-park
  operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: site-occupancy record logging, facility maintenance
  scheduling, supply-restock coordination, and guest-safety-concern
  flagging. CRITICAL: it is a smart-but-untrusted advisor. It returns
  a *proposal* (with a rationale + the fields it cited), never a
  committed record and NEVER a direct actuation -- every proposal's
  `:effect` is always `:propose`. Every output is censored downstream
  by `campgroundops.governor` before anything touches the SSoT.

  This advisor NEVER drafts a direct evacuation-order execution, a
  direct emergency-services / law-enforcement contact, or an override
  of a guest-safety-authority decision -- those are permanently out of
  scope for this actor, not merely un-implemented (Wave 4
  person-facing-service safety guardrail, ADR-2607152500).
  `campgroundops.governor`'s `scope-exclusion-violations` independently
  re-scans every proposal for exactly this failure mode (a compromised
  or confused advisor drifting into scope it must never touch) and
  HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :site-id    str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-site-occupancy-record
  "Draft a site-occupancy log entry (booking / check-in / check-out
  data). Pure logging of observed occupancy state -- never a
  guest-safety-authority action."
  [_db {:keys [site-id patch]}]
  {:op         :log-site-occupancy-record
   :site-id    site-id
   :summary    (str site-id " のサイト利用記録を記録: " (pr-str (keys patch)))
   :rationale  "サイトの予約/チェックイン/チェックアウト状況の記録のみ。安全当局の判断は含まない。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence 0.94})

(defn- propose-facility-maintenance
  "Draft a restroom / utility-hookup / road maintenance scheduling
  PROPOSAL only (never a direct maintenance-crew dispatch)."
  [_db {:keys [site-id patch]}]
  {:op         :schedule-facility-maintenance
   :site-id    site-id
   :summary    (str site-id " に関連する設備メンテナンス提案: " (pr-str (keys patch)))
   :rationale  "トイレ/ユーティリティフック/道路のメンテナンス日程調整の提案のみ。実施の決定は人間の施設管理者が行う。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence 0.89})

(defn- propose-supply-restock
  "Draft a propane / firewood / potable-water restock coordination
  proposal (never a direct purchase/dispatch order)."
  [_db {:keys [site-id patch]}]
  {:op         :coordinate-supply-restock
   :site-id    site-id
   :summary    (str site-id " に関連する補給調整: " (pr-str (keys patch)))
   :rationale  "プロパン/薪/飲用水などの補給調整の提案のみ。発注確定は人間の施設管理者が判断する。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence 0.87})

(defn- propose-guest-safety-concern
  "Surface a guest/facility safety concern (fire risk, wildlife
  encounter, structural hazard) for HUMAN triage. This op ALWAYS
  escalates in `campgroundops.governor` -- never auto-committed at any
  phase -- regardless of how confident the advisor is that the concern
  is real, and it never itself executes an evacuation order, contacts
  emergency services, or overrides a guest-safety-authority decision."
  [_db {:keys [site-id patch]}]
  {:op         :flag-guest-safety-concern
   :site-id    site-id
   :summary    (str site-id " のゲスト安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "ゲストの安全に関する観察事実の報告のみ。常に人間の確認・対応が必要。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-site-occupancy-record (propose-site-occupancy-record _db request)
                   :schedule-facility-maintenance (propose-facility-maintenance _db request)
                   :coordinate-supply-restock (propose-supply-restock _db request)
                   :flag-guest-safety-concern (propose-guest-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually issue an evacuation order and contact emergency services directly")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :site-id (:site-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
