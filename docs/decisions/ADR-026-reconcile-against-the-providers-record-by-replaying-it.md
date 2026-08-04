# ADR-026: Reconcile against the provider's record by replaying it

- **Status:** Accepted
- **Date:** 4 August 2026
- **Amends:** [ADR-012](ADR-012-deduplicate-and-order-provider-callbacks.md) §terminal-absorption —
  one terminal state no longer absorbs; [ADR-015](ADR-015-time-out-processing-payments-to-failed.md)
  — supplies the mitigation it named
- **Related:** [ADR-017](ADR-017-simulate-providers-through-scheduled-signed-callbacks.md) (the
  export this reads), [ADR-019](ADR-019-refunds-own-their-callback-route-and-guard-over-refund-with-a-lock.md)
  §16.6 (Refund's named gap), [ADR-023](ADR-023-finish-the-lifecycle-claims-and-give-the-token-table-a-writer.md)
  (the refund sweeper), [ADR-008](ADR-008-cross-module-access-through-ports.md), SDD §13.1, §21.4
- **Migration:** V22

---

## 1. The gap, in three places that all pointed at the same missing job

`GET /sim/v1/reconciliation/{date}` has produced the provider's own daily truth since ADR-017, and
**nothing read it.** Its own javadoc says so: *"This is the input. It is not the job."*

Three separate pieces of shipped code lean on the job that did not exist:

- **ADR-015** times a stranded `PROCESSING` payment out to `FAILED` **with no evidence the payment
  failed**, and names reconciliation as the mitigation. Its failure message says outright that the
  attempt *"may still have succeeded at the provider and needs reconciliation."*
- **ADR-023** does the same for refunds and is explicit that the real answer *"does not exist yet"*.
- **ADR-019** lists a lost refund callback as Refund's known gap.

The cost is not an untidy row. When the provider actually collected, PayMesh holds a `FAILED`
payment, the Ledger never posts, and **the merchant is simply short — permanently, and silently.**

## 2. Decision: replay, do not diff

A scheduled job fetches the provider's report for a small window of recent UTC days and **replays
every terminal row through the same callback service a real provider callback goes through.** The
payment or refund aggregate decides what, if anything, changes.

There is no comparison against PayMesh's state anywhere in the job. That is the central decision.

The obvious design — read both sides, work out the difference, apply it — would be **a second copy of
the transition rules on the money path**. `PaymentIntent` already refuses `AUTHORIZED → SUCCEEDED`
from a callback, already refuses an amount the intent does not authorize (SDD §12.3), already judges
staleness before the state machine; the callback service already writes the attempt row and the
outbox event that makes the Ledger post. A job re-deriving any of that would diverge the first time
one of them changed, and nothing would notice.

What replaying costs: every row is replayed on every run, including the overwhelming majority that
were already correct. Each is one indexed lookup answering `DUPLICATE`. The bound is the day, not
the table.

## 3. The event id is the whole safety argument, and it has two halves

Deduplication is `(provider, eventId)`. The job mints its own, as
`recon:` + SHA-256 of `kind | id | terminal state | amount`:

- **Deterministic**, so re-running a day — which the schedule does nightly and an operator will do by
  hand — is a `DUPLICATE`, not a second application. Without this, a nightly job would re-apply the
  same outcome indefinitely; on the capture path that means collecting twice.
- **Prefixed**, so it can never collide with a real callback's id. A collision would swallow the
  replay as a duplicate and leave a genuine divergence unrepaired — **silently, looking exactly like
  success.**

Including the terminal values in the hash is what lets a row that *changed* since the last run (an
authorization later captured) be replayed again rather than mistaken for the same fact. Hashed rather
than concatenated because `ProviderEvent` caps an event id at 120 characters and a truncated key
collides silently.

The provider's own `updatedAt` travels as `occurredAt`, never the job's clock: it is what the
monotonic guard compares, so stamping "now" would let a reconciliation of an old day overwrite a
newer callback.

## 4. ONE terminal state stops absorbing, and it is narrow

This is the part that changes shipped money-path behaviour, so it is stated at length.

ADR-012 has terminal states absorb late callbacks, and that is right for every state that records
something that **happened**: the provider declined, the provider collected, the merchant cancelled. A
late event contradicting one of those is the provider being wrong or slow.

**A payment failed by ADR-015's sweeper records the opposite** — that the provider said *nothing at
all*. By the sweeper's own admission it is a guess in the safe direction. Absorbing the answer when
it finally arrives makes ADR-015's own mitigation unreachable: PayMesh holds a `FAILED` payment, the
provider holds the customer's money, and the state machine refuses the only event that could
reconcile them.

So `PaymentIntent.requireProviderTransition` gains exactly one exception, guarded by
`isUnansweredTimeout()`: `status == FAILED && failureCode == TIMEOUT_FAILURE_CODE`.

Why that is narrow rather than a general un-failing of payments:

- `provider_no_response` is written by **one caller** and means "nobody answered". A payment the
  provider declined carries the provider's own code and stays terminal forever.
- The **monotonic clock still applies on top**. This widens which states a provider may speak into,
  not whether a stale event may speak at all.
- Applying a confirmed outcome **overwrites the failure code**, which takes the payment back out of
  the window. A payment stays revisable exactly as long as it is genuinely unresolved.

The constant moved from `TimeOutProcessingPaymentsService` into `PaymentIntent`: once the aggregate
branches on a value, that value is part of its vocabulary, and leaving it in the application layer
would either invert the dependency direction or duplicate a string that must never disagree.

**This fixes late callbacks too, not only reconciliation.** That is a feature: it is the same fact
from the same authority, and it avoided building a second, reconciliation-only entrance to the state
machine — the thing §2 argues against.

`ProcessingTimeoutIntegrationTest` asserted the old behaviour and now asserts the new one. Its old
javadoc ended *"nothing in this codebase resolves the disagreement"* — a known gap stated honestly,
not a desired end state.

## 5. Why the fetch is HTTP to a service in the same JVM

It looks absurd: the simulator's `ExportReconciliationService` is one Java call away, and this goes
out through the loopback interface.

`ModuleBoundaryTest.noCapabilityImportsTheSimulator` has an **empty allowlist in both directions**.
SDD §13.2 says the simulator owns no PayMesh state; ADR-017 makes it removable from a deployment. A
direct call would break both instantly and silently: the "provider" would become a compile-time
dependency of the money path, and reconciliation would work against exactly one provider forever —
the one that can never be a real one.

Concretely, the loopback hop buys a single adapter that changes when the provider becomes an SFTP
drop or a signed CSV, and it exercises the real serialization, the real API key filter and the real
error paths. The integration test runs on a random port and proves the bytes cross the boundary.

## 6. `provider_refunds` gained a caller reference (V22)

`provider_payments` has had `callback_reference` since V13 — the caller's opaque string, echoed back.
`provider_refunds` never did, because `SimulatedRefund` was built when PayMesh had no refund receiver
at all; its javadoc says the dispatcher *"gains a refund row type in the PR that builds the
receiver"*. The receiver exists (ADR-019).

Without it, **half the provider's daily truth is unusable**: a refund row names a provider refund and
a provider payment and nothing PayMesh recognises. The column is nullable and stays nullable — rows
written before this have none, and inventing one would be fabricating provider data. The simulator
still holds no reference to PayMesh: it stores and echoes an opaque string it never parses.

## 7. Why an unreachable provider is the one failure that propagates

Everything else is counted, never rethrown — one bad row must not disable the pass.

`ProviderReportUnavailableException` is different, and the distinction is the point: a report that
was never read has repaired nothing, and returning an empty report instead would produce zero
examined and zero repaired — **identical to a quiet day.** A provider unreachable for a week would
otherwise report a clean reconciliation every night. Connection refused, a 500 and an unparseable
body all raise it.

## 8. Consequences

- ADR-015's and ADR-023's guesses are now revisable by the provider's own record. **A collected
  payment PayMesh timed out reaches `SUCCEEDED` and the Ledger posts its balance.**
- `TIMED_OUT` at this provider carries `capturedAmountMinor = 0`, so replaying it as `FAILED` turns
  the guess into a confirmation. **That reading is specific to this provider's file**: a real
  acquirer may report an outcome it does not yet know, and "unknown" must never be read as "nothing
  moved". Any status the job does not recognise is skipped rather than defaulted — every value in
  that switch moves money.
- The window is 3 days, not 1. A callback can be late by more than a day, a file can be amended, and
  a pass can fail to run; with a window of one, any of those skips a day forever.
- `reconciliation` is a capability package in `ModuleBoundaryTest`, reaching Payment and Refund
  through one allowlisted adapter each, and reaching nothing else. Nothing reaches back into it.
- **Known imprecision, recorded rather than hidden:** Refund answers `NOT_APPLICABLE` both for a
  settled refund and for a refund that does not exist, and the two are indistinguishable from the
  adapter. Both are counted `ALREADY_CONSISTENT`, because a settled refund is the common case and
  counting it as unresolved would make that number permanently large and meaningless. `REPAIRED` is
  exact either way. Closing it properly needs a distinct value on Refund's enum — Refund's PR.
- The job is off under the `dev` profile, like every other timer: it moves payments to `SUCCEEDED`
  and makes the Ledger post, so a tick landing mid-assertion would rewrite the rows under test.

## 9. Alternatives considered

**Report divergences without repairing.** Much smaller and much safer-sounding. Rejected because it
does not close the gap: the money stays wrong and a human stays in the loop for a condition nobody is
watching for. It is also the status quo — the export already *is* a report nobody reads.

**A dedicated repair entrance in Payment** (`markSucceededFromReconciliation(...)`). Avoids touching
ADR-012's absorption rule. Rejected: it is a second way to move a payment, reachable by a scheduled
job, bypassing the amount check, the staleness guard, the attempt row and the outbox event. The
narrow exception in one guard is a smaller and more auditable change than a parallel path.

**Run reconciliation more often than the sweeper's timeout, so payments are repaired while still
`PROCESSING`.** Requires no domain change and genuinely helps — it is why the interval is worth
tuning. Rejected as the *whole* answer: it is a race, and it does nothing for the payments already
sitting in the wrong state.
