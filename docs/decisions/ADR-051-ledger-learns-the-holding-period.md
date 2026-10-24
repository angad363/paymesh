# ADR-051: The ledger learns each merchant's holding period across the service boundary

- Status: Accepted (§4 decided: Option A — event-fed projection)
- Date: 2026-10-24
- Scope: A focused cross-service fix, not an extraction. It closes the cutover gate recorded in ADR-050
  "Review amendments (PR 14)": the extracted ledger cannot read settlement's per-merchant holding
  period, so its release job uses a flat default and would release a merchant with a custom period at
  the wrong time. Touches the ledger (monolith + `ledger/`) and, depending on §4, settlement (monolith
  + `settlement/`). Follows ADR-031 (the pending→available release job), ADR-039 (the `merchant_ref`
  event-fed projection — the house pattern), ADR-047 (settlement owns `settlement_configs`), and
  ADR-048 §4 (`api_credential_ref`, the most recent projection of the same shape).

## Context

The pending→available release job (ADR-031, `ReleaseAvailableFundsService.release`) decides a capture is
releasable when `now >= capturedAt + holdingPeriod`. The holding period is per-merchant, owned by
settlement, and stored in `settlement_configs` (settlement schema). A merchant with no row gets the
platform default **as a value, never a persisted row** — `GetSettlementConfigService` is explicit that
writing a default row would make "never configured" and "configured to exactly the default"
indistinguishable and would break a later change to the platform default.

In the monolith the ledger reads this in-process through `SettlementModuleHoldingPeriod` (its one
allowed boundary crossing, named by `ModuleBoundaryTest`) → `GetSettlementConfigService`. In the
extracted `ledger/` service that crossing is gone: `ledger_svc` has no grant on the `settlement`
schema (ADR-038), and PR 14 wired `HoldingPeriodPolicy` to a flat
`paymesh.ledger.release.default-holding-period` (7d), marked `ponytail:`. A merchant with a **custom**
holding period would be released early or late by the service.

This is inert today — `paymesh.ledger.release.enabled` defaults off in the service and dual path keeps
the monolith's release job (with the correct per-merchant period) authoritative — but it **gates the
release-flag cutover** (ADR-050 §5). The release flag cannot be flipped to the ledger service until the
service can read the real per-merchant period. This ADR removes that gate.

Two facts shape the fix:

1. **Settlement emits no config-change event today.** `GetSettlementConfigService.set(...)` just saves;
   there is no `outbox.append`. Settlement's outbox already carries `settlement.batch_cut` /
   `payout.paid` / `payout.returned`, so the outbox plumbing exists; the event does not.
2. **The release job is a background timer and tolerates lag and a brief outage.** It runs on a schedule
   (ADR-031), `uq_ledger_transactions_idempotency` on `funds-released:<pi>` makes double-release
   impossible regardless, and a capture it cannot release this pass is simply picked up next pass
   (invariant 9: non-authoritative read-side infrastructure may fail without corrupting financial
   state). So neither eventual consistency nor a momentary settlement outage threatens correctness —
   they only delay a release, which is the safe direction.

## Decision

### 1. The ledger keeps `HoldingPeriodPolicy`, with the same default-as-a-value semantics

Whatever the source chosen in §4, the ledger's `HoldingPeriodPolicy.forMerchant(merchantId)` returns the
merchant's configured period, falling back to the platform default **as a value** for a merchant with no
explicit config — mirroring `GetSettlementConfigService` exactly. A default row is never persisted or
projected, so a later change to `paymesh.ledger.release.default-holding-period` still reaches every
defaulted merchant. The release timing through the service must be **at least as correct as the
monolith's** — that is the acceptance bar.

### 2. The flat default stays as the floor, not the answer

The `ponytail:` flat default from PR 14 is not deleted; it becomes the fallback value for a merchant
with no explicit config, which is correct. What changes is that an explicitly-configured merchant is no
longer flattened to it.

### 3. Both processes stay consistent under dual path — but only the extracted ledger projects

The monolith ledger and the extracted ledger must compute the **same** period for the same merchant
while dual path is live, but they reach it by different routes, and that asymmetry is deliberate (the
same shape ADR-048 §4 used: the monolith merchant kept in-process ApiKey auth while emitting events so
`payment/` could project).

- **Settlement emits from both processes.** The monolith settlement module and the `settlement/` service
  both append `settlement.config.updated` in the same transaction as `set(...)`.
- **Only the extracted ledger projects.** The `ledger/` service gets the `settlement_config_ref` table
  and the projector and reads the period from it. The **monolith ledger is unchanged** — it keeps
  reading the period in-process through `SettlementModuleHoldingPeriod`, which is already correct, so it
  needs neither the table nor the projector. Adding a projection to the monolith would duplicate a read
  it already has.

Both therefore compute the same per-merchant period, and the cutover (flip
`paymesh.ledger.release.enabled`) just flips onto a service that now knows the real period.

### 4. How the ledger obtains the period — DECIDED: event-fed projection

**Option A is chosen.** §1–§3 hold regardless; this fixes the mechanism.

- **Option A — event-fed projection (chosen).** Settlement emits `settlement.config.updated`
  (`merchantId`, `holdingPeriodSeconds`) from its outbox in the **same transaction** as `set(...)`,
  monolith and `settlement/` both emitting. The ledger projects into a local `settlement_config_ref`
  read model (`merchant_id`, `holding_period_seconds`), fed by a projector on that event;
  `HoldingPeriodPolicy` reads the projection, default-as-value for a missing row. A **one-time backfill**
  emits `settlement.config.updated` for every existing `settlement_configs` row so merchants configured
  before the event existed are projected (the same initial-state step ADR-039 took). *Tradeoff:* the
  ledger stays self-contained on the money path with no runtime dependency on settlement, and it matches
  every other read model in the system; it costs a new event, a projector, a ref table, dual-path
  emission, and the backfill, and the period is eventually consistent (a change lags by the projection
  delay — acceptable, like merchant-suspension lag in ADR-039).
- **Option B — synchronous internal read (rejected).** Settlement adds an internal, read-key-authed
  holding-period read (e.g. `GET /internal/v1/settlement/merchants/{merchantId}/holding-period`, or a
  bulk variant), routed through the gateway; the ledger's `HoldingPeriodPolicy` calls it over HTTP,
  caching within a sweep pass so repeated merchants cost one call. *Tradeoff:* always current, no
  projection table, no backfill, less code; but it couples the money-moving release job to settlement
  being reachable at sweep time and reintroduces a cross-service synchronous call the projection pattern
  deliberately avoids. Rejected to keep the ledger self-contained on the money path.

**Chosen: Option A.** The ledger stays self-contained on the money path, consistent with `merchant_ref`
and `api_credential_ref`. The backfill is a one-time cost paid once at cutover, and after it the ledger
never calls settlement to move money.

## Consequences

- The ADR-050 release-flag cutover gate is removed: once this ships, the ledger service computes the same
  per-merchant period the monolith does, and `paymesh.ledger.release.enabled` can be flipped after the
  usual under-load soak.
- Under Option A, settlement gains its first config-lifecycle event (`settlement.config.updated`), which
  is useful beyond the ledger (reporting, an audit of holding-period changes) and is cheap to keep.
- Under Option A, the backfill is a required cutover step and must run before the release flag is
  flipped, or custom-period merchants projected-as-default would release early until their next config
  change. It belongs in the cutover runbook next to the flag flip.
- The flat default remains meaningful as the fallback for unconfigured merchants, so
  `paymesh.ledger.release.default-holding-period` must stay in step with settlement's platform default
  (they are the same value today; a test pins each at 7d — they cannot cross the module boundary to
  compare directly).

## Review amendments (PR for ADR-051)

The review surfaced one real regression and three smaller fixes.

- **The holding-period-only setter must not lose a concurrent full PUT (fixed).** The first cut had the
  two-argument `set(merchantId, holdingPeriod)` pre-read the row *outside* any transaction to carry its
  `payoutDestination` / `minimumPayoutMinor` into the four-argument setter, which then re-read inside the
  transaction and overwrote all fields — so a concurrent full PUT between the two reads was silently
  reverted. Fixed by giving each overload a single read-merge-save-emit inside one `TransactionTemplate`
  (the holding-period overload merges only the period, keeping the other fields from the in-transaction
  read), sharing a private `saveAndEmit`. This restores the pre-change single-read concurrency profile.
  Fixed in both `backend/` and `settlement/`.
- **Only the extracted ledger projects; the monolith keeps its in-process read (clarified).** §3's
  original "both ledger processes project it" was loose. The monolith ledger is unchanged and reads the
  period in-process; only `ledger/` projects. This is the ADR-048 §4 shape and is now stated explicitly
  in §3.
- **The backfill carries the real change time (fixed).** `V42` stamped `occurred_at = now()`; it now uses
  `settlement_configs.updated_at`, so a future audit consumer of `settlement.config.updated` sees genuine
  change times. The projection only snapshots the latest value, so the `>=` guard is unaffected either way.
- **The projector javadoc described a dead-letter path that does not exist (fixed).** The extracted
  service's `KafkaEventListener` bounded-retries then advances the offset (no DLT until a capability is
  Kafka-only). The javadoc now describes that accurately; the throw-on-bad-payload behaviour is unchanged.

**Cutover step (reminder):** V42 must be relayed to the ledger service before `paymesh.ledger.release.enabled`
is flipped, or custom-period merchants would project as the default until their next config change.
