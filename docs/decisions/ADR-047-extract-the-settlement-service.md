# ADR-047: Extract the settlement service — the money-moving cut leaves the process

- Status: Accepted (cut race decided as option A, §4; spec approval pending)
- Date: 2026-10-20
- Scope: PayMesh Phase 3C, PR 12. New `settlement/` module (port 8088), the ninth deployable. Own
  Flyway history, own `settlement` schema reached as the fenced `settlement_svc` role. One monolith-side
  addition (§2), dual path. Follows ADR-032 (settlement's three journals and the reversal rule), ADR-031
  (`MERCHANT_AVAILABLE`), ADR-038 (schema-per-service), ADR-040 (gateway), ADR-044 (the synchronous
  extraction and fail-closed-by-amount precedent), ADR-046 (the most recent extraction). Plan of record:
  `docs/phase-3-microservices-extraction-plan.md` §3C PR 12.

## Context

Settlement cuts a merchant's available balance into a batch, submits a payout to the provider, and
records the outcome. Three facts about the code shape this PR more than the plan does:

1. **Settlement never posts journals.** It emits `settlement.batch_cut`, `payout.paid` and
   `payout.returned` to its outbox. The Ledger consumes those events (`SettlementLedgerHandler`,
   `PostSettlementJournalsService`) and posts the `available → SETTLEMENT_IN_TRANSIT → BANK_CASH`
   journals itself. So the plan's "requests ledger postings via the Ledger API" describes something
   the code does not do. Settlement's money effect is already event-shaped.
2. **The synchronous read is a list, not a balance.** `AvailableFunds.contributions(merchantId)` returns
   each payment's available amount (`PaymentContribution(currency, paymentIntentId, amountMinor)`), and
   the cut nets that against every batch that has already taken from it. The plan's "ledger balance
   across the wire" is therefore a per-payment contribution read — a larger payload, and the one the
   batch's arithmetic actually needs.
3. **The provider speaks HTTP both ways.** Submission already goes out over HTTP (`HttpPayoutGateway`,
   ADR-041), and the provider's outcome comes back as a signed HTTP callback to
   `/internal/v1/payout-callbacks/{provider}`. The plan's "events to/from provider-sim" does not match
   the contract (ADR-017, ADR-041 kept it HTTP deliberately).

## Decision

### 1. Divergences from the plan, recorded

- **(a) Ledger read is a contributions read, over an HTTP port, in the monolith.** The plan's
  "balance across the wire" becomes `GET /internal/v1/ledger/merchants/{merchantId}/available-contributions`,
  served by the monolith, shared-key authenticated. The monolith's in-process `LedgerModuleAvailableFunds`
  stays; settlement gets a second `AvailableFunds` implementation, `LedgerHttpAvailableFunds`, selected
  by `paymesh.settlement.ledger.mode` (`in-process` default, `http`). This is ADR-044's seam, not a new
  one. The ledger has no HTTP API of its own until PR 14; this endpoint is a narrow read bolted onto the
  monolith for one caller, and PR 14 replaces it.
- **(b) No ledger posting API in PR 12.** Journals stay event-driven (§4). The plan's "posting via the
  Ledger API" is deferred to PR 14, where posting becomes an idempotent API.
- **(c) Payout outcomes arrive as HTTP callbacks, routed by the gateway.** Not events. The gateway gains
  `/internal/v1/payout-callbacks/**` → `settlement-uri`, `@Order(0)`, unauthenticated at the edge and
  HMAC-checked at the deployable, the same posture as the provider callbacks today.

### 2. The monolith side, and dual path

The monolith keeps all settlement code and its `settlement` schema access. One monolith change is
added: the `GET .../available-contributions` read in §1(a), wired only so that it is reachable. No
monolith migration.

**Exclusive ownership of the cut.** This is the one place dual path can go wrong. The monolith's
`SettlementSweeper` is a timer, and the settlement service runs its own. Two sweepers would cut the same
merchant's balance twice. The existing flag `paymesh.settlement.sweep.enabled` already gates the
sweeper; PR 12 makes that flag the single switch: **exactly one process has it `true`**. Settlement
service: `true`. Monolith: `false`. Rollback is flipping the two back, and the cutover runbook flips
them as one step, never one at a time.

Callbacks follow the gateway route (§3), so a payout's outcome reaches exactly one process. Both
processes share the physical database, so a callback landing on either still updates the same rows.

### 3. Gateway

`settlementRoutes` (`@Order(0)`) forwards `/internal/v1/payout-callbacks/**` to
`paymesh.gateway.settlement-uri` (`:8088`). It is a subset of `/internal/**`, so `@Order(0)` is
load-bearing for the same reason as `riskRoutes`. There is no client-facing settlement route in PR 12:
`/api/v1/settlements/**` and `/api/v1/settlement-config/**` (the reads and config PR 11 left in place)
also move to `settlement-uri`, rate-limited as client traffic, with `@Order(0)`.

### 4. Journals: unchanged, and the race is the open question

Settlement's three journals are posted by the Ledger from `settlement.batch_cut` and `payout.*`, exactly
as today. The ledger's consumer name and topic are unchanged; settlement's outbox relay publishes them
to Kafka, and the monolith consumes them there.

**The race ADR-032 closed is reopened, and this is the decision that needs your answer.** ADR-032 closed
the release-versus-cut race with a row lock on the ledger's available accounts, taken in the same
database as the cut. Settlement can no longer take that lock: it reads contributions over HTTP
(a snapshot), and it cannot touch the `ledger` schema (fenced). Between the read and the batch commit, a
release or a refund can change a payment's available amount. Settlement then cuts a batch larger than
what is available, and the Ledger's consumer posts the transfer anyway, leaving `available` negative
(ADR-031 accepted negative available as honest, but not as a silent outcome of a race).

Decided: **option A.**

- **A (accepted): ledger-side re-validation on consume.** The Ledger checks, when it consumes
  `settlement.batch_cut`, that the merchant's total available balance in the batch's currency still
  covers the batch (a merchant-level check; the per-payment wording of the original text is withdrawn
  -- the invariant that matters is that available never goes negative from this batch, and available
  already excludes earlier batches). If it does not, it posts nothing and emits `settlement.batch_rejected`, and settlement reverts the cut as a new reversal
  transaction (ADR-032's rule). Money is never moved on a stale read. Cost: a new event and a
  revert path; the cut is eventually rejected rather than refused up front.
- **B: accept the bounded race.** Keep the snapshot read, document that a concurrent release or refund
  in the lag window can produce a negative available balance, and reconcile it with the next release.
  Cheapest. Accepts a real, bounded money-correctness gap in a PR whose job is to not add one.
- **C: defer the cut into PR 14.** Settlement stays in the monolith until the ledger is an API. Safest;
  delays the extraction the plan puts in wave 3C.

### 5. Ledger read failure: fail closed, per merchant

The cut is a scheduled job, not a confirm path, so ADR-044's by-tier fallback does not apply. If the
contributions read fails, settlement **skips that merchant for this tick** and records the skip. No
batch is cut on a read it did not get. The next tick retries. This is the same money-safe default as
ADR-030's, and it costs only latency: a merchant's payout is delayed by one interval during a ledger
outage, never mis-sized.

### 6. Schema, fenced role, and the deferred trigger

Settlement's own Flyway history: `V1` adopts `settlement_configs`, `settlement_batches`,
`settlement_items`, `payouts`, `payout_callbacks` and `provider_payouts` (`baseline-on-migrate`), and
`V2` creates the platform tables. The batch-net-equals-items deferred constraint trigger
(`tr_settlement_batches_total`, `tr_settlement_items_total`) and the append-only and immutability triggers
move with the tables and stay inside the `settlement` schema, as the plan says. Connects as
`settlement_svc`. The merchant-status question is settled by the code: settlement has no merchant-status
gate today, so none is added.

### 7. In-flight batches during cutover

A batch is **in flight** if it is `PENDING`, or a payout is `SUBMITTED` without a callback. The cutover
runbook is:

1. Set the monolith's `paymesh.settlement.sweep.enabled=false`, so no new batch is cut there.
2. Drain the monolith's outbox (as in ADR-046 §2) so `settlement.batch_cut` and `payout.*` are published.
3. Re-point the gateway's `settlement-uri` (routes in §3) to `:8088`, and start the settlement service
   with `paymesh.settlement.sweep.enabled=true`.

In-flight payouts keep their state in the shared `settlement` schema, so the new process picks up their
callbacks. Rollback reverses the three steps in the opposite order.

### 8. Review amendments (PR 12)

1. **Payouts wait for the ledger.** A payout was due the instant its batch was cut, so the provider
   could be paid for a cut the Ledger later rejected (`settlement.batch_rejected`). The Ledger now emits
   `ledger.batch_posted` (aggregate `settlement_batch`; `settlementBatchId`, `merchantId`, `amountMinor`,
   `currency`) in the same transaction as a successful `settlement.batch_cut` journal. Settlement consumes
   it (consumer `settlement-ledger-batch-posted`) and moves the batch `PENDING_PAYOUT -> POSTED`
   (`SettlementBatch.markPosted`); payouts are submittable only when their batch is `POSTED`
   (`PayoutRepository.findDue`), and `markPaid` requires `POSTED`. Migration `V3` (settlement) / `V41`
   (monolith) adds the status and backfills existing `PENDING_PAYOUT` batches to `POSTED`, since they
   predate the gate; a cut still in flight at the upgrade that the Ledger would reject is the residual
   case, to be drained before the upgrade.
2. **The stale-batch check holds a lock.** The re-validation in §4 summed available without a lock, so a
   refund or release could commit between the check and the post. The check now takes a row lock on the
   merchant's available account (`openAndLock`, `SELECT ... FOR UPDATE`) before reading, and the release
   job and the refund reversal that debits available take the same lock (after the payment-journal lock,
   one fixed order). Release only credits available, so its lock is conservative.
3. **The revert is idempotent.** A `settlement.batch_rejected` for a batch already `RETURNED` is handled
   and returns silently (no ERROR); the ERROR remains only for a payout that has actually left the
   building. Both copies of the service (monolith and `settlement/`) carry identical logic.
4. **The ledger read is not public.** `GET /internal/v1/ledger/**` is service-to-service only (shared key);
   the gateway's `/internal/**` pass-through would have exposed it, so a gateway route at `@Order(0)`
   answers 404 and forwards nothing.
5. **Ledger-read hygiene.** `LedgerHttpAvailableFunds` closes the HTTP response on every path (including
   non-2xx and malformed bodies), and a malformed merchant id from the ledger is a
   `LedgerReadUnavailableException` (a recorded skip, §5) rather than an `IllegalArgumentException` that
   aborts the whole tick.

## Consequences

- Settlement's money path gets one new synchronous dependency, the contributions read, and a clear
  failure rule for it (§5). It does **not** gain a synchronous dependency on the ledger for posting.
- The cut's race (§4) is decided as A: the ledger re-validates on consume, and settlement reverts a
  rejected cut as a new reversal. That adds one event (`settlement.batch_rejected`) and a revert path,
  both in this PR.
- Dual path here, like PR 10 and PR 11, depends on exclusive sweeper ownership (§2). It is the easiest
  step to get wrong in deployment, so the runbook is part of the decision.
- The `shared` subtree is duplicated into a ninth module until PR 16, the stated recipe cost.
