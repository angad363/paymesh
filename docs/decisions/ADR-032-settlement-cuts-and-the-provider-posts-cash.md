# ADR-032: Settlement cuts against available, and the provider's word posts the cash

**Status:** Accepted
**Date:** 2026-08-15
**Implements:** SDD §17.1–§17.6 and §13.3–§13.4. Builds on ADR-018 (the Ledger), ADR-031 (the
settleable balance), ADR-019 (the over-refund lock), and ADR-012 (callback dedup and ordering).

## Context

ADR-031 made a merchant's balance settleable — funds clear their holding period and move from
`MERCHANT_PENDING` into `MERCHANT_AVAILABLE` by a journal the ledger posts to itself. It closed by
naming Settlement as the thing that would pay against `available`, and left two limits recorded as
open item 20 that Settlement must not start paying out without confronting.

Settlement is where money leaves the platform. That makes it the first capability that credits a
cash account, and the first that hands an instruction to a provider expecting the provider to move
real money. Both are new shapes of risk for this codebase, and both are governed by the same
invariant everything on the money path is: a committed movement must never be lost, silently
duplicated, or become unauditable.

## Decision

**A scheduled job cuts a merchant's available balance into a batch, submits a payout to the provider,
and `BANK_CASH` is credited only when the provider's signed callback says the money landed.** A
terminal failure returns the funds to available by a new reversal journal. The money moves
`available → SETTLEMENT_IN_TRANSIT` on cut and `in-transit → BANK_CASH` on paid, or
`in-transit → available` on failure.

### The provider's word posts the cash, and nothing else

Crediting `BANK_CASH` is the moment the platform records that money left. It is posted from the
signed payout callback and never from PayMesh's own submission — the same rule Payment follows for a
capture (submitting is not confirming), applied to a payout. A 2xx from the provider on submission
means "accepted for processing", not "settled"; treating it as settlement would post cash for money
that a later callback might say never moved.

As with every posting since ADR-018 §3, **there is no internal port that writes to the ledger.**
Settlement commits its own rows and an `outbox_events` row in one transaction; the Ledger consumes
the event and posts the journal. So every settlement journal traces to a committed Settlement row,
and a redelivered event is a no-op on the journal's idempotency key rather than a second posting.
`SettlementLedgerHandler` is one class handling three event types, because the three journals differ
by exactly one line each; the `processed_events` consumer name is still one per event type, so a
duplicate of any one is deduped independently.

### Three journals, and a failure is a new one

`settlement.batch_cut` debits `MERCHANT_AVAILABLE`, credits `SETTLEMENT_IN_TRANSIT`. `payout.paid`
debits in-transit, credits `BANK_CASH`. `payout.returned` debits in-transit, credits available.

The returned journal is a **new** transaction, never an edit of the cut. The batch that failed and
the funds that came back are both on the record, and the money is settleable again — a batch marked
`RETURNED` and available restored, rather than a cut journal quietly rewound. This is ADR-018's rule
that corrections are new reversals, not deletes, holding on the way out just as Refund holds it on a
capture.

### The cut nets each payment against every prior batch

A batch is per currency, and its items are the payments that fund it. Each item is what a payment
still has in available, **netted against every earlier, non-returned batch that already took from
it** — so a payment cannot be settled twice across two batches. That the item sum equals the
available balance is not asserted in the application; `tr_settlement_batches_total`, a deferred
trigger, makes the batch's ledger debit equal the sum of its items, and the two agree because both
are derived from the same entries.

A net of zero or less is not a batch, and negative means the merchant owes PayMesh — carried in
`available` (which ADR-031 allows to go negative) until fresh payments cover it, because that is the
only place it can be carried honestly.

### Open item 20's interleave is closed here, with a lock and a test

ADR-031 left the release/refund interleave open on principle: a lock whose only proof is an argument
is false coverage. Settlement is the change that makes `available` being wrong get *paid out*, so the
lock lands here. `lockPaymentJournals` takes a row lock on a payment's capture journal before reading
its available position, in **both** the release job and the refund reversal — the two writers that
can disagree about a payment. It is ADR-019 §4.1's over-refund lock applied a second time, and for
the same measured reason: a deferred constraint trigger cannot fix it, because the trigger's query
runs on the snapshot of the statement that queued it and both writers pass.

### §17.6's invariants are database triggers

Immutability triggers freeze posted batch, item and payout rows; the deferred batch-total trigger
ties a batch's debit to its items; and two CHECKs bound the new account types. `AccountType` gains
`SETTLEMENT_IN_TRANSIT` and `BANK_CASH`, and — the trap ADR-031 §Consequences named — **both**
`ck_ledger_accounts_type` and `ck_ledger_accounts_owner` learn about them, because missing the second
is how V29 failed the first time. The application pre-checks turn a violation into a readable error;
the constraint is what makes it true.

### The config columns ADR-031 deferred

`settlement_configs` gains the payout destination and minimum that ADR-031 left for "a capability
that does not exist" to define. It exists now, so the columns have readers: no destination means the
merchant is not payable and no batch is cut (cutting one would move money into a transit account with
nowhere to go); below the minimum, cutting costs more than it moves. `PUT /api/v1/settlement-config`
replaces every setting, PUT-not-PATCH, and the holding-period-only setter the release job uses leaves
the two new columns untouched.

### The simulator grows payouts, and submission is idempotent

Settlement is the first consumer of SDD §13.3–§13.4, so `POST /sim/v1/payouts` and `provider_payouts`
arrive with it. Submission is idempotent on the payout's own id
(`uq_provider_payouts_external_reference`), so a resubmitted payout — the safe response to a stuck one
whose callback was lost — does not pay twice. Each payout is submitted in its own transaction under a
row lock, so one malformed payout costs one payout rather than the batch.

## Consequences

- **`inSettlementMinor` joins the balance**, for the same reason `availableMinor` did in ADR-031: a
  merchant watching a payout would otherwise see the amount vanish from every figure between cut and
  confirmation. `reserved` stays omitted — nothing holds funds — and a zero there would still be a
  placeholder rather than an answer.
- **One named cycle between the Ledger and Settlement.** `ModuleBoundaryTest` records the single
  edge: Settlement emits events the Ledger posts from, and the Ledger reads Settlement's per-payment
  available contributions to cut against. It is allowlisted explicitly rather than left to drift.
- **The loop is scheduled and off under `dev`**, like every timer in this codebase, so the money
  path is driven directly in tests (`SettlementLoopIntegrationTest`: cut → in-transit, paid →
  `BANK_CASH`, failed → available) rather than by waiting for a tick.

### Known limits

- **No FX and no fee deduction.** A batch is one currency and nets nothing out, because there is no
  fee schedule and no cross-currency payout. SDD §17.3's fee split has nothing to split against.
- **A payout is confirmed all-or-nothing.** The simulator answers `SUCCEEDED` or `FAILED` for the
  whole payout; partial settlement (some of a batch lands, some returns) is not modelled, because the
  ledger movement it would need has no provider event to trace to yet.
- **Open item 20's held-slot leak is still open** (a capture fully refunded before it cleared never
  leaves the release candidate set). It is not on the payout path — it costs a slot in the release
  batch, not a wrong `BANK_CASH` — so it stays where ADR-031 put it, waiting on the same partial
  index.
- **The lost-payout-callback path relies on resubmission.** A payout whose callback never arrives
  stays non-terminal and is resubmitted when it comes due; the provider dedups on the external
  reference. There is no settlement reconciliation reading the provider's daily record the way
  ADR-026 does for payments — that is a later capability, and until it exists a permanently lost
  callback is a stuck payout rather than a lost one.
