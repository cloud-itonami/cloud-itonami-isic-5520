(ns campgroundops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`campgroundops.operation` ->
  `campgroundops.governor` -> `campgroundops.store`) through a scenario
  built from this repo's own `campgroundops.store/demo-data` (real seeded
  sites `site-1`/`site-2`/`site-3`), adapted from this repo's own
  `campgroundops.sim` demo driver (`clojure -M:dev:run`, confirmed by
  actually running it before this file was written: every id it uses
  (site-1, site-2, site-3, plus the deliberately-unseeded site-99) checks
  out against `campgroundops.store/demo-data`'s real seed, and every
  disposition it produces -- three clean phase-3 auto-commits, two
  always-escalate approvals, and three distinct HARD-hold reasons
  (`:site-unverified`, `:effect-not-propose`, `:scope-excluded`) --
  matches `campgroundops.governor`'s own documented checks precisely, so
  it was safe to adapt rather than author a scenario from scratch), and
  rendered deterministically -- no invented numbers, no timestamps in the
  page content, byte-identical across reruns against the same seed
  (verified by diffing two consecutive runs before shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [campgroundops.store :as store]
            [campgroundops.operation :as op]
            [campgroundops.advisor :as advisor]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :campground-manager :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real site ids from
  `campgroundops.store/demo-data` (site-1 and site-2 registered +
  verified, site-3 registered but NOT yet verified):

  site-1: a clean `:log-site-occupancy-record` patch is a phase-3
  no-capital-risk auto-commit (governor clean, this op is in phase 3's
  `:auto` set). A clean `:coordinate-supply-restock` order under the
  governor's $500 cost threshold ALSO auto-commits at phase 3.

  site-2: a clean `:schedule-facility-maintenance` proposal auto-commits
  at phase 3 (the third op in phase 3's `:auto` set).

  Then two ALWAYS-escalate dispositions, each approved by a human
  campground manager: site-1's `:coordinate-supply-restock` proposal
  whose estimated cost ($940) exceeds the governor's $500 threshold
  (`high-cost-supply-restock?`), and site-2's `:flag-guest-safety-concern`
  (a member of `always-escalate-ops` -- NEVER auto-eligible, at any
  phase, per `campgroundops.phase`'s own documented invariant).

  Then three DISTINCT HARD-hold reasons, none of which ever reach a
  human (a human approver cannot override a HARD violation):
    - site-3 (registered but `:verified?` is still false in the seed
      data): `:log-site-occupancy-record` HARD-holds on
      `:site-unverified` -- the governor never trusts a proposal's own
      claim about a site, only the site's own store record.
    - site-1, advisor forced to claim `:effect :commit` instead of
      `:propose` (the same `reify Advisor` test hook
      `campgroundops.sim` itself uses): `:schedule-facility-maintenance`
      HARD-holds on `:effect-not-propose` -- any effect other than
      `:propose` is, by construction, a claim to directly actuate
      outside governance.
    - site-2, advisor drifts into the permanently-excluded
      evacuation-order-execution / emergency-services-contact scope
      (`:out-of-scope? true`, the same test hook `campgroundops.sim`
      itself uses): `:log-site-occupancy-record` HARD-holds on
      `:scope-excluded` -- this actor's charter structurally excludes
      that territory, evaluated unconditionally on every proposal.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; site-1: clean site-occupancy-record logging -- phase-3 auto-commit,
    ;; no capital risk.
    (exec! actor "site1-log" {:op :log-site-occupancy-record :site-id "site-1"
                               :patch {:guest "Alvarez family" :check-in "2026-07-20" :nights 4}})

    ;; site-2: clean facility-maintenance scheduling -- phase-3 auto-commit.
    (exec! actor "site2-maint" {:op :schedule-facility-maintenance :site-id "site-2"
                                 :patch {:item "water spigot repair, loop B" :urgency "routine"}})

    ;; site-1: clean supply-restock, under the $500 cost threshold --
    ;; phase-3 auto-commit.
    (exec! actor "site1-restock-lo" {:op :coordinate-supply-restock :site-id "site-1"
                                      :patch {:item "propane" :quantity 3 :estimated-cost 75}})

    ;; site-1: supply-restock OVER the $500 cost threshold -- ALWAYS
    ;; escalates regardless of confidence, approved by a human campground
    ;; manager.
    (exec! actor "site1-restock-hi" {:op :coordinate-supply-restock :site-id "site-1"
                                      :patch {:item "bulk firewood pallet order" :quantity 250 :estimated-cost 940}})
    (approve! actor "site1-restock-hi")

    ;; site-2: guest-safety-concern flag -- ALWAYS escalates at any phase,
    ;; approved by a human campground manager.
    (exec! actor "site2-safety" {:op :flag-guest-safety-concern :site-id "site-2"
                                  :patch {:concern "downed tree limb blocking loop B fire lane" :confidence 0.9}})
    (approve! actor "site2-safety")

    ;; site-3 (registered, NOT yet verified): HARD hold on
    ;; :site-unverified, never reaches a human.
    (exec! actor "site3-log" {:op :log-site-occupancy-record :site-id "site-3"
                               :patch {:guest "waitlist hold"}})

    ;; site-1, advisor forced to claim a direct :effect :commit instead of
    ;; :propose -> HARD hold on :effect-not-propose, never reaches a human.
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (exec! actor-direct "site1-maint-direct" {:op :schedule-facility-maintenance :site-id "site-1"
                                                 :patch {:item "50A hookup breaker inspection"}}))

    ;; site-2, advisor drifts into the permanently-excluded evacuation /
    ;; emergency-services scope -> HARD hold on :scope-excluded, never
    ;; reaches a human.
    (exec! actor "site2-outofscope" {:op :log-site-occupancy-record :site-id "site-2"
                                      :out-of-scope? true
                                      :patch {}})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger site-id]
  (last (filter #(= (:site-id %) site-id) ledger)))

(defn- status-cell [ledger site-id]
  (let [f (last-fact-for ledger site-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- site-row [ledger {:keys [site-id name registered? verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc site-id) (esc name)
          (if registered? "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
          (if verified? "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
          (status-cell ledger site-id)))

(defn- ledger-row [{:keys [t op site-id disposition basis reason by]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc site-id)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> reason name)
                    (some-> disposition name)
                    (some->> by (str "approved by "))
                    ""))))

(defn- coordination-row [{:keys [op site-id value payload]}]
  (let [approved-by (:approved-by payload)]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc (name op)) (esc site-id)
            (esc (pr-str (dissoc value :site-id)))
            (if approved-by
              (str "<span class=\"ok\">approved by " (esc approved-by) "</span>")
              "<span class=\"muted\">auto-committed</span>"))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`campgroundops.governor`/`campgroundops.phase`) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-site-occupancy-record</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &middot; site must be independently registered + verified in the store</span></td></tr>"
   "        <tr><td><code>:schedule-facility-maintenance</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &middot; proposal only, never a direct maintenance-crew dispatch</span></td></tr>"
   "        <tr><td><code>:coordinate-supply-restock</code></td><td><span class=\"ok\">phase-3 auto-commit when clean and estimated cost &le; $500</span> &middot; <span class=\"warn\">ALWAYS human approval above $500, regardless of confidence</span></td></tr>"
   "        <tr><td><code>:flag-guest-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval, at every rollout phase &middot; never auto-commit-eligible &middot; never itself executes an evacuation order or contacts emergency services</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        sites (store/all-sites db)
        site-rows (str/join "\n" (map (partial site-row ledger) sites))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        coordination-rows (str/join "\n" (map coordination-row (store/coordination-log db)))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-5520 &middot; camping grounds, recreational vehicle parks and trailer parks</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Camping grounds, recreational vehicle parks and trailer parks (ISIC 5520) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · guest-safety-concern flagging always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Sites</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>campgroundops.store</code> via <code>campgroundops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Site</th><th>Name</th><th>Registered</th><th>Verified</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     site-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Committed coordination log</h2>\n"
     "    <p class=\"muted\">Only auto-committed and human-approved proposals ever reach the SSoT — a HARD hold never writes here.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Site</th><th>Value</th><th>Provenance</th></tr></thead>\n"
     "      <tbody>\n"
     coordination-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (CampgroundGovernor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Site registration/verification, proposal effect, and scope exclusion are independently recomputed, never trusted from the advisor's proposal; guest-safety-concern flagging is always a human campground manager's call, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold, escalation and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Site</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "coordination-log records )")))
