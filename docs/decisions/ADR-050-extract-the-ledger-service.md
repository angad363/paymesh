# ADR-050: Extract the ledger service — the financial source of truth leaves last and whole

- Status: Accepted (§7 decided: Option 1 — posting stays event-driven, synchronous posting API deferred)
- Date: 2026-10-24
- Scope: PayMesh Phase 3E, PR 14. New `ledger/` module (port 8090), the eleventh deployable and the
  last capability to leave the monolith. Own Flyway history, own `ledger` schema reached as the fenced
  `ledger_svc` role. The `ledger` schema is extracted **structurally unchanged** — the deferred
  `debits = credits` trigger and the entry/transaction immutability triggers (V15/V18) stay exactly
  where they are. Follows ADR-018 (the ledger posts from committed state via events, never an internal
  posting API), ADR-031 (`MERCHANT_AVAILABLE` and the pending→available release job), ADR-038 (fenced
  roles), ADR-040 (shared HS256 at the edge), ADR-047 (settlement reads ledger balances over HTTP), and
  ADR-048 (payment posts money only by emitting events, and calls no synchronous ledger API). Plan of
  record: `docs/phase-3-microservices-extraction-plan.md` §3E "PR 14 — Ledger (last)".

**Numbering note.** The plan labels this "ADR-049". That number was taken by the captured-after-close
defect fix (merged). This is ADR-050; PR 15 becomes ADR-051 and PR 16 ADR-052.

## Context

The plan put the ledger last for one reason, and it governs every decision here: every earlier service
can tolerate eventual consistency or a brief outage, but the ledger's invariants — double-entry
balance and append-only history — cannot be distributed and cannot survive a distributed transaction.
So the ledger leaves only after everything that feeds it is already stable, and it leaves whole, with
its invariants still enforced inside its own schema by the same triggers that enforce them today.

Four facts from the code shape the decisions below.

1. **The ledger posts money only from events, and has no synchronous posting caller.** Its inputs are
   exactly three event handlers: `PaymentSucceededLedgerHandler`, `RefundSucceededLedgerHandler` and
   `SettlementLedgerHandler` (`settlement.batch_cut`, payout paid, payout returned). A search for a
   synchronous posting client or HTTP post into the ledger returns nothing. ADR-048 made this explicit
   for the money path: payment posts by emitting `payment.succeeded` / `refund.succeeded`, and does not
   call the ledger.
2. **A synchronous balance READ already exists and is already cross-service.** The merchant-facing
   `GET /api/v1/balances` (`BalanceController`) and the internal
   `GET /internal/v1/ledger/merchants/{merchantId}/available-contributions` and
   `/internal/v1/ledger/available-merchants` (`LedgerReadController`) are served by the ledger today,
   and settlement already consumes the internal read over HTTP (`LedgerHttpAvailableFunds`, ADR-047).
3. **The release job is a timer, and it moves money.** `AvailableFundsReleaseSweeper` +
   `ReleaseAvailableFundsService` move pending funds to `MERCHANT_AVAILABLE` on a schedule (ADR-031).
4. **Posting is idempotent per transaction.** Every journal carries a transaction-level idempotency key
   (`settlement-batch-cut:<id>`, `payout-paid:<id>`, and the payment/refund equivalents), enforced by
   `uq_ledger_transactions_idempotency`. A redelivery of the same business fact is a no-op whoever
   posts it.

## Decision

### 1. Schema, fenced role, platform tables

The `ledger` schema holds the ledger accounts, transactions, entries, balances and their triggers. `V1`
adopts the live tables (`baseline-on-migrate`) **with the V15/V18 triggers intact inside the schema** —
nothing about the invariants moves or weakens. `V2` creates the platform tables
(`outbox_events`, `processed_events`, `idempotency_records`) with a nullable `outbox_events.merchant_id`
(the house accounts, e.g. bank-cash, have no merchant). Connects as `ledger_svc`, which has no grant on
the `payment`, `merchant`, `settlement` or `risk` schemas.

### 2. Posting stays event-driven — the recorded divergence from the plan

**The plan's "posting a transaction is now an idempotent API" is not adopted.** Nothing calls such an
API: payment and settlement both post by emitting events the ledger consumes, and ADR-048 forbade a
synchronous ledger call on the money path. Building a posting API with zero callers would be exactly the
internal posting API ADR-018 refused — a second way into the financial source of truth with no committed
state change to reconcile a posting against. The ledger stays an **event consumer** of the three event
types in Context fact 1. The money path is unchanged by this extraction; only the process boundary moves.

Whether to build the posting API anyway, as the ledger's long-term contract, is the open question in §7.

### 3. The balance read is the synchronous surface the ledger owns

PR 14 formalises the ledger as the owner of the balance read that already exists (Context fact 2). The
merchant-facing `GET /api/v1/balances` and the internal `/internal/v1/ledger/**` reads move to the
ledger service. Edge auth is unchanged: ADR-040 shared HS256 at the gateway, the internal reads keep
their read-key. No read contract changes shape.

### 4. `ledger.transaction.posted` — not emitted yet

The plan wants the ledger to emit `ledger.transaction.posted`. **No consumer exists** — ADR-048 recorded
that nothing in payment waits on the ledger's posting, and nothing else subscribes either. An event with
no subscriber is published, dispatched to an empty handler list and stamped published — pure cost and a
contract we would have to keep stable for no reader. **Decision: do not emit it in PR 14.** It is added
by the first PR that has a real consumer (reporting or an external ledger feed are the likely ones), which
is also the PR that can define its payload against a concrete need. *Tradeoff:* a future consumer waits
one PR for the event; set against emitting and versioning a money event nobody reads, the wait is cheaper.
(This mirrors how `order.paid` was allowed to sit with an empty handler list only because it was already
needed by the order lifecycle; here there is no such need yet.)

### 5. Dual path — the gravest rollback in the programme

The monolith keeps every ledger class, its `ledger`-schema access, its three event handlers and its
release sweeper, so rollback is re-pointing the gateway's `ledger-uri` (balance reads) and
settlement's ledger-read URL back to the monolith, and flipping the release flag back. No monolith
migration, no mode flag — the ADR-045/046/047/048 shape. During dual path, **both** the monolith's
ledger consumers and the ledger service's consumers subscribe to the same
`payment.succeeded` / `refund.succeeded` / `settlement.*` events (two consumer groups), and both share
the one `ledger` schema. That shared schema makes state safe in one direction and not the other:

- **Posting is idempotent across processes.** Every journal is keyed by its transaction idempotency key
  (`uq_ledger_transactions_idempotency`). A business fact posted by one process and redelivered to the
  other is refused at the unique index, not double-posted — the same argument ADR-048 made for
  `provider_callbacks`. Both consumer groups landing the same `payment.succeeded` is therefore safe:
  whichever posts first wins, the other's insert is a no-op.
- **The release job is NOT idempotent across processes.** `AvailableFundsReleaseSweeper` runs on a timer
  in both processes, and two releases would move the same pending funds to `MERCHANT_AVAILABLE` twice.
  The ADR-047/048 exclusive-ownership rule applies: a single flag, `paymesh.ledger.release.enabled`,
  true in exactly one process, flipped in one cutover step. It **defaults off in the ledger service** so
  a stock start cannot run it beside the monolith. Two release jobs double-crediting available balance is
  a financial-correctness breach, so this is not optional.

**Cutover runbook (proposed):** (1) turn off the monolith's release sweeper; (2) let both consumer
groups drain so no `payment.*` / `refund.*` / `settlement.*` event is mid-flight; (3) re-point the
gateway's `ledger-uri` and settlement's ledger-read URL to the ledger service; (4) turn on the ledger
service's release sweeper. Dual path **stays until the money path has run clean against the separate
ledger under load** — this is the one extraction where the rollback window is kept open deliberately long.

### 6. Gateway prefixes

A `ledgerRoutes` (`@Order(0)`) forwards the client read `/api/v1/balances/**` and the internal
`/internal/v1/ledger/**` to `paymesh.gateway.ledger-uri` (`http://localhost:8090` by default,
`PAYMESH_GATEWAY_LEDGER_URI` override), rate-limited as client traffic for the former and unrate-limited
for the internal read. Settlement's `paymesh.settlement.ledger.url` is re-pointed from the monolith to
the gateway, the ADR-041 rule that one place decides which process answers. `GatewayLedgerRouteTest`
covers precedence over the `/api/**` and `/internal/**` catch-alls.

### 7. The synchronous posting API — DECIDED: deferred, posting stays event-driven

Given §2, nothing calls a synchronous ledger posting API today. The plan specifies one. **Option 1 is
chosen:** the ledger is extracted whole and unchanged, posting stays event-driven (the three existing
event consumers), and the synchronous posting API is deferred to the first PR with a real caller — so
its contract is designed against a concrete need rather than guessed now. This keeps ADR-018's rule
intact and is the smallest correct surface.

The rejected alternative (**build the idempotent `Idempotency-Key`-scoped posting endpoint now**, ADR-009,
as the plan's long-term contract) would realise the plan and hand future callers a ready API, but it
ships an endpoint with no caller to exercise it and re-introduces the ADR-018 tension of a synchronous
path into the financial source of truth that no committed state change reconciles against. When a real
caller appears, that PR adds both the posting API and the `ledger.transaction.posted` event (§4)
together, against its actual need.

## Consequences

- The money path is unchanged by the extraction: the same events, the same journals, the same triggers,
  now in their own process. Only the balance-read host and the release-job owner move.
- The release-job exclusive-ownership flip is the single most dangerous cutover step in the whole
  programme — double release is a direct balance-correctness breach — so it is in the runbook, not a
  footnote, and the flag defaults off in the new service.
- Dual path is held open longer here than anywhere else, on purpose: the ledger is the one place a silent
  divergence is unrecoverable, so it runs in parallel under load before the monolith's ledger is retired.
- `ledger.transaction.posted` is deliberately absent until a consumer exists, so PR 15/16 (or whichever
  PR first needs it) must add it rather than assume it is already emitted.
- The `shared` subtree is duplicated into an eleventh module until PR 16, the stated recipe cost.

## Review amendments (PR 14)

The review and implementation of this PR surfaced one real cutover gap and two small fixes.

- **The per-merchant holding period cannot follow the release job into the ledger service (cutover
  gap, must be closed before the release flag is flipped).** In the monolith, the release job reads each
  merchant's holding period from settlement's tables in-process (`SettlementModuleHoldingPeriod`). The
  fenced `ledger_svc` role has no grant on the `settlement` schema, so the ledger service cannot do that
  read. The extraction wires `HoldingPeriodPolicy` to a flat `paymesh.ledger.release.default-holding-period`
  (7d, settlement's default) in the ledger module, marked `ponytail:`. This is **inert today**: the
  release flag defaults off in the ledger service, and dual path keeps the monolith's release job — with
  the correct per-merchant period — authoritative. But a merchant with a *custom* holding period would be
  released at the wrong time if the flag were flipped. So, before the cutover flag-flip in §5, the
  ledger needs a settlement-owned read of the holding period (an event-fed projection, the ADR-039
  pattern, or a synchronous settlement API), or an explicit decision to accept the flat default. This is
  a gate on the release-flag cutover, recorded so it is not lost.
- **Malformed `merchantId` on the internal read now returns 400, not 500 (fixed).** `LedgerReadController`
  parses a `merchantId` path variable via `MerchantId.from`, which throws `IllegalArgumentException`. The
  `LedgerExceptionHandler` was scoped to `BalanceController` only, so the exception was unmapped — a 500.
  Because §6 makes this route edge-reachable, the handler now also covers `LedgerReadController` and maps
  `IllegalArgumentException` to 400. Fixed in both `backend/` and `ledger/`; regression test
  `rejectsAMalformedMerchantIdWith400`.
- **The gateway blocks are replaced by a forward, consistent with the established posture.** The old
  `blockedLedgerReadRoutes` (which 404'd `/internal/v1/ledger/**` when there was no ledger service)
  is removed, and the gateway `SecurityConfiguration` now permits `GET /internal/v1/ledger/**` at the
  edge — the same shape as the already-permitted `/internal/v1/risk-evaluations/**` and the provider /
  refund / payout callbacks: a machine-to-machine route authenticated by a shared key
  (`LedgerReadKeyFilter`, fail-closed on a blank key) at the deployable, not by a token at the edge.
