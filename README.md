# cloud-itonami-isic-5520

**Camping grounds, recreational vehicle parks and trailer parks** — ISIC Rev.4 class 5520.

A coordination-only actor for campgrounds, RV parks, and trailer parks, behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-site-occupancy-record, schedule-facility-maintenance, coordinate-supply-restock, flag-guest-safety-concern (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Site verified** — target site-occupancy record must exist AND be registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — directly issuing/executing an evacuation order, directly contacting emergency medical services or law enforcement, and overriding a guest-safety-authority decision are permanently blocked. This actor never has the authority to directly execute a guest-safety-authority decision — see CRITICAL below.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: site-occupancy-record logging only (approval-gated)
  - Phase 2: + facility maintenance scheduling, supply-restock coordination (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (guest-safety concerns always escalate; over-threshold supply-restock proposals always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## CRITICAL — scope

This is a consumer-facing hospitality operations-coordination actor, **not** a guest-safety-authority. It coordinates back-office logistics only. It **NEVER**:

- Directly issues or executes an evacuation order.
- Directly contacts emergency medical services or law enforcement.
- Overrides a guest-safety-authority decision.

`flag-guest-safety-concern` only ever *surfaces* an observed concern (fire risk, wildlife encounter, structural hazard) for a human to triage — it is never a member of any phase's `:auto` set, at any phase, and it is always escalated to human sign-off. A `coordinate-supply-restock` proposal whose estimated cost exceeds the governor's cost threshold ($500) is likewise always escalated to a human, regardless of confidence.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/campgroundops/governor_test.clj` — unit tests of governor hard checks and scope exclusion
- `test/campgroundops/advisor_test.clj` — advisor proposal shape and consistency
- `test/campgroundops/phase_test.clj` — rollout phase logic
- `test/campgroundops/governor_contract_test.clj` — full graph integration, audit trail
- `test/campgroundops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `campgroundops.store` — SSoT (MemStore, String-keyed site directory, append-only ledger)
- `campgroundops.advisor` — contained intelligence node (mock + real-LLM seam)
- `campgroundops.governor` — independent compliance layer
- `campgroundops.phase` — staged rollout (0→3)
- `campgroundops.operation` — langgraph-clj StateGraph
- `campgroundops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-services) fleet. See ADR-2607121000, ADR-2607152500, and ADR-2615100000 for design decisions.
