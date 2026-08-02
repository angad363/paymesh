# ADR-018: Post the ledger from events, and keep its invariants in the database

- **Status:** Accepted
- **Date:** 2 August 2026
- **Supersedes:** nothing
- **Related:** [ADR-001](ADR-001-start-with-modular-monolith.md) (modular monolith),
  [ADR-003](ADR-003-use-opaque-prefixed-public-identifiers.md) (prefixed ids),
  [ADR-010](ADR-010-transactional-outbox-in-postgresql.md) (outbox),
  [ADR-016](ADR-016-in-process-event-dispatch-before-kafka.md) (event delivery),
  SDD §15

---

## 1. Context

Until this change PayMesh had no financial source of truth. A payment intent reaching
`SUCCEEDED` was operational state and nothing more — `README.md` said so in as many
words: *"no balance moves anywhere in this codebase."* Every mechanism needed to fix
that was already built and idle: the outbox writes `payment.succeeded` in the payment's
own transaction (ADR-010), a relay reads it, a dispatcher hands it to subscribers, and a
`processed_events` inbox makes redelivery safe (ADR-016). Order was the only consumer.

SDD §15 specifies the Ledger in full: nine account types, six tables, six endpoints,
balance holds, a versioned `account_balances` projection, and a worked posting that
splits a payment three ways with a platform fee. Most of it has no caller in a codebase
where Settlement and Refund do not exist.

The governing invariant is the one from `CLAUDE.md`: **committed money movement must
never be lost, silently duplicated, or become unauditable.** This is the module where
that stops being a design principle and becomes rows in tables.

## 2. Decision

Build the double-entry core and nothing else:

- `ledger_accounts`, `ledger_transactions`, `ledger_entries` (V15).
- A consumer of `payment.succeeded` that posts a two-entry journal.
- `GET /api/v1/balances`, summed from the entries.

And put every invariant that protects money in **PostgreSQL**, not in Java:

| SDD §15.6 invariant | Enforced by |
|---|---|
| Debits equal credits | `tr_ledger_entries_balanced`, a DEFERRED constraint trigger checked at COMMIT |
| One currency per transaction | Composite foreign keys on `(transaction_id, currency)` and `(account_id, currency)` |
| Positive amounts, direction stored separately | `ck_ledger_entries_amount_positive` + a `direction` column |
| Idempotency key unique | `uq_ledger_transactions_idempotency` |
| Entries cannot be updated or deleted | `tr_ledger_entries_immutable`, plus the same on the header |
| A journal moves only its own merchant's money | The same deferred trigger, checked at COMMIT |

The domain checks the same rules so a caller gets a readable sentence rather than a
constraint violation. **The domain check is the error message; the constraint is the
guard.** That is the rule `README.md` already states for the rest of the codebase — the
integration tests bypass the application layer entirely and stay red where they should.

## 3. Why the posting is driven by an event and not by an API

SDD §15.3 specifies `POST /internal/v1/ledger/transactions`. It is not built.

Nothing would call it. The platform is one deployable (ADR-001), the only thing that
posts is `PostPaymentCapturedService`, and the only thing that drives that service is an
event. Adding the endpoint would create a second, authenticated, idempotency-keyed path
into the financial source of truth — reachable by anything holding a service token, with
no originating event to reconcile against.

The property this buys is worth stating positively: **every posting traces to an event,
which traces to a committed state change.** A journal cannot exist without a payment that
produced it. That is stronger than the endpoint would give, and it is free while the
Ledger is in-process. `ModuleBoundaryTest.noCapabilityImportsTheLedger` keeps it true by
refusing any direct call as well.

The endpoint arrives with the extraction. SDD §30.1 puts that last deliberately.

## 4. Why the posting has no platform fee

SDD §15.2's example splits a ₹999.00 capture into ₹970.00 merchant net and ₹29.00
platform fee revenue. This posts two entries instead, both gross:

```
DEBIT   provider-clearing:INR        99900
CREDIT  merchant:mrc_x:pending:INR   99900
```

There is no fee schedule anywhere in this codebase — no rate, no rounding rule, no
per-merchant pricing, no effective dates. The third entry could only be computed from a
number invented at the point of posting, and it would then sit in immutable rows that no
later correction can edit. A made-up rate in the financial source of truth is worse than
a missing one.

Dropping the fee weakens the detail, not the invariant: debits equal credits either way.
When a fee schedule exists it adds a third entry to *new* postings and leaves every
existing one alone, which is precisely what an append-only ledger is for.

## 5. Why there is no `account_balances` projection

SDD §15.5 specifies one, described as the "fast authoritative projection", with debit and
credit totals and a version column. `GET /api/v1/balances` sums the entries instead.

A projection is a second copy of a number the entries already determine, and the failure
mode of a second copy is that it disagrees with the first. That disagreement is silent,
it is found by a merchant rather than by a test, and the repair is to recompute it from
the entries — which is the query being avoided. A SUM cannot drift from what it sums.

The cost is real and bounded: O(entries for one merchant), growing for the life of the
account. `ix_ledger_entries_account` covers direction and amount so it stays an
index-only scan, which pushes the problem a long way out without removing it. The code
carries a `ponytail:` marker naming the upgrade path.

Note that SDD §15.6's sixth invariant — "balance projection and journal entries update in
the same transaction" — is satisfied trivially rather than skipped. There is no
projection to fall out of step.

## 6. Consequences

**Good.**

- A `SUCCEEDED` payment now moves a balance, and the balance is auditable back to the
  entries that produced it.
- Two consumers now read one event, which is the first real exercise of `processed_events`
  being keyed on `(consumer_name, event_id)` rather than the event alone. Order and the
  Ledger dedupe and fail independently.
- The Ledger imports nothing from Payment and nothing imports the Ledger — the strictest
  boundary in the codebase, matched only by the simulator's, and the right one for the
  module scheduled for extraction last.

**Bad, and known.**

- **The balance is eventually consistent.** Capture and then read immediately and the
  balance may lag by a relay tick. It is correct, not instant.
- **No reversal path exists.** Nothing can currently undo a posting, because nothing needs
  to until Refund. The immutability triggers are what make the reversal the *only*
  available option when it arrives rather than the disciplined one.
- **`PROVIDER_CLEARING` is one account per currency, not per provider.** Reconciling
  against a specific provider's statement will want the split. PayMesh has one provider
  concept and a single shared callback secret to match, so the distinction cannot be made
  yet anyway.
- **A capture of zero posts nothing.** Reachable via a provider callback, and a journal of
  two zero entries would balance perfectly while recording no movement. The service
  returns early; the alternative is a handler that throws and an event that never drains.
- **The idempotency key is keyed on the payment, not the capture.** A genuine *second*
  capture of one intent would be silently refused rather than posted. Unreachable today —
  `PaymentIntent` permits one capture — and the safe direction to fail in. When partial
  captures become repeatable the key needs the capture sequence, and
  `LedgerTransactionTest.keysIdempotencyOnThePaymentRatherThanTheEvent` is what will force
  the decision.
- **An event that fails to post is retried forever with no dead-letter and no alert.**
  Inherited from ADR-016, unchanged, and still the largest hole in the delivery design.

## 7. Identifiers

ADR-003's planned prefix list predates the Ledger. Two are added: `lac_` for accounts and
`ltx_` for transactions. Three letters rather than two so neither can be confused with a
future `la_`/`lt_`, and neither is a prefix of the other.

Ledger *entries* get a `BIGSERIAL` and no public identifier — the only sequential id in
the schema. ADR-003 governs things the API addresses, and nothing addresses an individual
entry; the journal is the unit a caller names.

## 8. Alternatives considered

**Post synchronously from `CapturePaymentIntentService`.** One method call, no event, no
lag, and the balance is correct the instant the API returns. Rejected: it puts Payment in
the Ledger's import graph in the direction that matters, makes the two impossible to
separate, and means a ledger failure rolls back a capture that the provider has already
performed. The money moved out there whether or not PayMesh could write it down.

**Build all of SDD §15 now.** Holds, the projection, the internal API, all nine account
types. Rejected: most of it is unreachable until Settlement and Refund, and unreachable
code in the financial source of truth is code nobody has a way to test against a real
caller.

**Enforce the balance rule in Java only.** Simpler, and every test would pass. Rejected
because it is exactly the class of guard this codebase has repeatedly found insufficient
— the pre-check that a refactor removes and nothing notices. The deferred trigger fires
for the application, for a migration, and for a human at a `psql` prompt.

**A composite tenant foreign key for the journal/entry merchant match.** The pattern V5, V6 and
V8 already use, and the first thing tried. It does not compose here: platform accounts carry a
NULL `merchant_id`, and a composite key containing a NULL matches nothing — so provider clearing
could never appear in any journal. The check moved into the deferred trigger instead. It was found
by review after the first implementation committed a cross-tenant journal cleanly, which is worth
recording: the balance stayed arithmetically correct (money is attributed by the account's owner)
while the audit header named a different merchant, so nothing would have read as an error.

**A per-row `CHECK` for the balance rule.** Impossible: a journal balances across rows,
and the first entry of every transaction ever written would fail on insert. Deferring to
COMMIT is what makes the check expressible at all.
