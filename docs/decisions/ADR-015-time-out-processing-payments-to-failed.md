# ADR-015: Time a stranded `PROCESSING` payment out to `FAILED`, and say what that costs

## Status

Accepted

> **This is the most consequential decision in the Payment capability so far, and it is the least
> certain.** Everything else in ADR-011 through ADR-014 refuses an action to protect a fact. This one
> *asserts* a fact — that a payment did not happen — on the strength of a clock. It can be wrong, and
> §4 is about what happens when it is.
>
> ADR-014 is its sibling and lands in the same PR. §6 says why neither is complete alone.

## Context

`PROCESSING` is the one payment-intent state with no exit.

Cancel is refused from it **by design** (ADR-011 §5), and the reason is good: an in-flight attempt
may already have succeeded at the provider, so cancelling locally could erase a payment that really
happened. So the only way out is a provider callback — and §0.4 of the design spec is explicit that a
callback may never arrive.

The blast radius is not one intent. An intent holds its order's **only** live slot
(`uq_payment_intents_live_per_order`), so a stuck intent is a stuck order:

- no second intent — `409 ORDER_HAS_ACTIVE_PAYMENT_INTENT`,
- no cancel on the intent — `409`, by design,
- no re-creating the order — the merchant's own `merchant_order_ref` collides,
- and from ADR-014, the order will not even expire, because the sweeper skips orders with a live
  intent.

Recovery is a database edit by hand. `V8` wrote this down at the point of definition, ADR-011 §5
repeated it, the design spec's §7 item 2 called it *"the single largest known hole in the design"*,
and `project-status.md` carries it as open item 2. It has been named four times and fixed zero.

**Nothing here is hypothetical about whether callbacks get lost.** The endpoint is the seam a
Provider Simulator plugs into; there is no provider today, so *every* confirmed intent in this
codebase sits in `PROCESSING` until something tells it otherwise.

## The decision that has to be argued

Three shapes were on the table.

| Candidate | Releases the slot? | What it asserts |
|---|---|---|
| **A.** Time out to `FAILED` | Yes | "We believe this payment did not happen" |
| **B.** Time out to a new non-terminal state, e.g. `UNRESOLVED` | Only if the slot rule widens to include it | "We do not know what happened" |
| **C.** Do nothing until reconciliation exists | No | Nothing — the hole stays open |

**C is what every previous PR chose, and it is no longer defensible.** It is the status quo that has
been named four times. The cost of choosing it again is not neutral: it is a *certain* stuck order,
every time a callback is lost, with a manual recovery path and no bound on how long the order stays
dead.

**B is the honest-looking answer and it does not survive contact with the slot rule.** A
non-terminal `UNRESOLVED` that does not release the slot solves nothing — the order is still dead,
just with a better-worded intent attached to it, which is worse than useless because it *looks*
handled. And a non-terminal state that *does* release the slot has **exactly the same failure mode as
A**: the merchant creates a second intent and may collect twice. It buys one thing — a status that
does not lie about what is known — at the price of an eleventh status in the enum and the CHECK, a
migration to widen `uq_payment_intents_live_per_order`'s exclusion set, and a new terminality
question at every callsite that asks "is this intent finished?".

**That price would be worth paying if the vocabulary were the problem. It is not.** `FAILED` with a
failure code of `provider_no_response` says the same thing `UNRESOLVED` would, in a field that
already exists, and it says it to every reader — the API, the event payload, the timeline row, and
whatever queries them. What matters is that the *reason* is unambiguous, not that the *status* is.

So: **A**, and the rest of this document is the cost.

## Decision

### 1. An intent that has sat in `PROCESSING` beyond a configurable age moves to `FAILED`

`FAILED` releases the order's slot, which is the entire operational point: the merchant can create a
fresh intent and try again. A timeout that did not release the slot would not have been worth
building.

### 2. The failure code must not look like a decline

`provider_no_response`, never `do_not_honour` or anything from a provider's vocabulary. The issuer
did not refuse — **it said nothing at all**, and those two are different facts that a support agent,
a reporting query and the reconciliation job that does not exist yet all have to tell apart at a
glance. The message spells it out:

> *"The provider did not report an outcome within the allowed time. This attempt may still have
> succeeded at the provider and needs reconciliation."*

The timeline row is `SYSTEM`, with a **null** `actor_id` — not `PROVIDER`. Marking it `PROVIDER` would
be the platform putting words in a provider's mouth, and it is the first thing anyone auditing a
disputed payment will look at.

### 3. The `payment_attempts` row is left completely untouched, and that is load-bearing

It keeps its `provider_reference` — what a reconciler matches against the provider's own settlement
file — and, critically, **its `last_provider_event_at` stays NULL**.

That column is ADR-012's monotonic ordering guard. Writing a synthetic timestamp into it would make a
genuine late callback judge itself `IGNORED_STALE` and vanish — **destroying the one piece of evidence
that this timeout was wrong.** A late `SUCCEEDED` callback must land, be refused as
`IGNORED_TERMINAL` because the intent is now terminal, and be **stored**. That `provider_callbacks`
row, with that outcome, against a `FAILED` intent, *is* the divergence, and it is queryable.

### 4. The age is generous, and it is the only safety margin there is

Default **1 hour**, configurable as `paymesh.payments.processing-timeout.age`. Far beyond any
plausible provider round trip.

The two directions are not symmetrical, and this is the whole argument:

- **Too short** → a real payment recorded as failed, the slot released, a second intent created, and
  the customer charged twice. Money taken with nothing on this side to show for it.
- **Too long** → a stranded order stays stranded a while longer. Visible, and with a manual route
  out.

Only one of those takes money. So the age is set where a false positive is implausible rather than
where a stuck order clears quickly, and the `interval` (how often the sweep runs) is a separate,
harmless knob that must not be confused with it.

The age is checked **twice**: in the candidate query, and again under the row lock. The second is not
redundant — a re-confirm after a 3DS challenge puts the intent back into `PROCESSING` with a fresh
`updated_at`, and a candidate selected on the old timestamp would otherwise be failed on the strength
of a wait that had already restarted.

`updated_at` is the age clock, not `payment_attempts.last_provider_event_at` (§3) and not
`created_at`. It is stamped by the transition that put the intent into `PROCESSING`, so
"`PROCESSING` since" is exactly what it reads.

### 5. Same shape as ADR-014's sweeper, for the same reasons

Locked per intent with the **no-merchant** `findForProviderCallbackForUpdate` — the timeout has no
token for the same reason a callback has none, and the merchant is derived from the row. The lock is
also what serializes a timeout against a callback arriving at the same instant: whichever takes the
row first wins, and the other re-reads and finds itself ineligible. **The provider's answer always
beats the absence of one**, because the re-check requires `PROCESSING` and a callback has already
moved it.

Batched, one transaction per intent, idempotent by the same re-check, tenant-agnostic and
tenant-safe. `ProcessingTimeoutSweeper` holds one call and one log line; every rule lives in
`TimeOutProcessingPaymentsService`, a plain `final` class with an injected `Clock`. Off under the
`dev` profile, which is what the suite runs under.

Every timeout logs at **WARN**, not INFO. Each row is a payment whose real outcome is unknown, and a
platform routinely timing payments out is saying something about its provider integration.

## Consequences

### The residue, stated plainly

- **A real payment can be recorded as failed.** If the attempt did succeed at the provider, PayMesh
  now holds a `FAILED` intent for a collection that happened. The slot is released, the merchant may
  create a second intent, and the customer may be charged twice. **This is a real, accepted risk of
  this decision, not an edge case being waved away.**
- **The mitigation is a generous age plus reconciliation, and reconciliation does not exist**
  (SDD §21.4, §24.1). Everything in §2 and §3 exists to make the bad case *findable* by a job that has
  not been written: the failure code, the untouched attempt, the stored `IGNORED_TERMINAL` callback.
  Today nothing reads any of it. **The age is doing all the work**, which is why lowering it is a
  money decision and not a tuning decision, and why `application.yaml` says so at the property.
- **The right time to revisit this is when the Provider Simulator lands**, because at that point a
  reconciliation pass over the provider's own records becomes possible for the first time — and with
  one, the age can come down and the divergence stops being permanent.
- **Nothing alerts.** A WARN in a log is not an alert, and there is no metric, no dashboard and no
  notification. A platform quietly timing out ten payments an hour would look exactly like one timing
  out none.

### Everything else

- **Every state in `PaymentIntentStatus` is now reachable except `PARTIALLY_REFUNDED` and
  `REFUNDED`**, which belong to the Refund capability and stay unreachable — verified by grep.
- **`PROCESSING` still has no *merchant* exit, and must not gain one.** The timeout is a `SYSTEM`
  action after a long wait, not a cancel; `CANCELLABLE` is unchanged and ADR-011 §5 stands.
- **This does not make a lost callback harmless.** It bounds how long the damage lasts and moves it
  from "stuck forever" to "resolved, possibly wrongly, within an hour".
- **Composes with ADR-014, and neither is complete alone.** ADR-014's sweeper skips orders with a live
  intent, so before this decision an intent stuck in `PROCESSING` would have blocked its order's
  expiry *indefinitely* — trading one indefinite hang for another. This releases the slot, and the
  next expiry sweep then handles the order. They were filed as two open items and turned out to be
  one.
- **A merchant sees a `FAILED` intent they cannot distinguish from a decline** without reading
  `failureCode`. That field is in the API response and in the `payment.failed` payload for exactly
  this reason, and dropping it from either would make an unresolved payment look like a refused one.

## Deliberately out of scope

- **Reconciliation itself.** SDD §21.4 and §24.1. It is the real answer and it is a capability, not a
  method.
- **Timing out any other state.** `REQUIRES_ACTION` looks similar and is not: it has a merchant cancel
  (ADR-011, ADR-012), so an abandoned challenge is the merchant's call to make and not a job's.
- **Retrying the provider.** There is no provider to retry and no outbound call anywhere in Payment
  (design spec §0.5).
- **Refunding the second collection when the double-charge happens.** The Refund capability does not
  exist, and neither does the Ledger that would have to record the reversal.
- **Alerting or metrics.** Named above as residue rather than dismissed; it belongs with SDD §26
  observability, which is not started.
