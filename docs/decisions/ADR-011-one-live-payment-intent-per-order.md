# ADR-011: Let an order hold at most one live payment intent, enforced by a partial unique index

## Status

Accepted

## Context

An order says what is owed. A payment intent says how it is being collected. Nothing
in the schema so far stops a merchant from opening a second intent for an order that
already has one, and if both reach the provider the merchant collects twice for a
single obligation. That is the governing invariant failing in its most visible form:
committed money movement that is silently duplicated.

SDD §11.6 *permits* the restriction to one live intent per order rather than
requiring it. This ADR takes it, because the alternative — allowing several intents
and reconciling their sum against the order's amount — needs a running total that is
correct under concurrency, and there is no Ledger yet to hold one. A restriction the
database can state in one line is available today; the general case is not.

## Decision

### 1. The index is the enforcement. The application check only writes the message

```sql
CREATE UNIQUE INDEX uq_payment_intents_live_per_order
    ON payment_intents (merchant_id, order_id)
    WHERE status NOT IN ('FAILED', 'CANCELLED');
```

A rule enforced only in application code is not enforced. Two concurrent
`POST /api/v1/payment-intents` for one order read the table, both find no live
intent, and both insert: `existsLiveForOrder` is a check, not a lock, and nothing
between the read and the write holds the answer still. The partial unique index is
what makes the second one lose.

`CreatePaymentIntentService` still calls `existsLiveForOrder` before it writes, and
that call is **never trusted**. It exists so the common, uncontended case produces a
409 explaining the conflict instead of a constraint violation surfacing from the
adapter. Deleting it entirely must leave every test green, and §2.7 of the design
makes that an explicit test property — the same way Order proved
`uq_orders_merchant_ref`.

`JpaPaymentIntentRepository.save` catches `DataIntegrityViolationException`, walks
the cause chain to Hibernate's `ConstraintViolationException`, and throws
`OrderHasActivePaymentIntentException` — `409 ORDER_HAS_ACTIVE_PAYMENT_INTENT` —
only when the constraint name is `uq_payment_intents_live_per_order`. This is
`JpaOrderRepository`'s shape, unchanged. Narrowing **by constraint name** rather than
by guessing from the aggregate matters here: the other integrity failures reachable
on that insert are the composite foreign keys, and an intent naming another tenant's
order reported back as "you already have one" would be both wrong and a hint about
another merchant's data.

### 2. The exclusion set is exactly `FAILED` and `CANCELLED`

Those two are the only statuses that release the slot. `SUCCEEDED`,
`PARTIALLY_REFUNDED` and `REFUNDED` keep occupying it, because an order that has
already been paid must not acquire a second intent — a refund does not make the
order collectable again, it makes it a refunded order.

### 3. Every state a customer can strand an intent in has a route out — except one

**A slot that cannot be released kills the order, and a dead order is worse than the
overpayment the index prevents.** The index frees the slot only on `FAILED` or
`CANCELLED`, so a stuck intent is a stuck order: the merchant cannot create a second
intent (`409`), cannot mark the order paid, and cannot re-create the order without
colliding with their own `merchant_order_ref`. There is no way out through the API.
The index is therefore only defensible alongside this table:

| State | Route out | Lands in |
|---|---|---|
| `REQUIRES_PAYMENT_METHOD` | merchant cancel | **exists now** |
| `REQUIRES_CONFIRMATION` | merchant cancel | next PR (attach/confirm) |
| `REQUIRES_ACTION` | merchant cancel | the PR after (provider callbacks) |
| `AUTHORIZED` | merchant cancel | the PR after that (manual capture) |
| `PROCESSING` | **none. Deliberate.** | §4 |

`REQUIRES_ACTION → CANCELLED` **was missing from the first draft of the design**, and
its absence would have been fatal. A customer who abandons a 3DS challenge, closes
the tab, or lets it expire is ordinary behaviour on any payment page — not a failure
mode, not rare, and not something the merchant did wrong. Without that transition the
single most common way a checkout ends would permanently destroy the order. A state
machine that only exits through success is not a state machine.

### 4. The `REQUIRES_ACTION` cancel race is real and is not resolved here

If the customer completes the challenge *after* the merchant cancels, the late
`SUCCEEDED` callback arrives for a `CANCELLED` intent. It is refused as terminal and
moves no money, which is the correct local answer — but PayMesh now believes the
payment was cancelled and the provider believes it succeeded, and they are both
acting on that belief.

The window is narrow and it is not zero. Nothing in this design closes it: the
`provider_callbacks` row records the refusal with an outcome that makes it findable,
and reconciling the disagreement is reconciliation's job (SDD §24.1). Stating the
race is the point. Pretending a cancel is instantaneous at the provider would be the
kind of assumption that is discovered in production.

### 5. `PROCESSING` is uncancellable, which is a named deferral with a known cost

An intent in `PROCESSING` has an attempt in flight that **may already have succeeded
at the provider**. Cancelling locally could therefore erase a payment that really
happened — money taken from a customer with no record of it on this side, which is
strictly worse than a stuck order. So `PROCESSING` has no merchant cancel, and its
only exit is a provider callback that may never arrive.

The full answer is a `PROCESSING` timeout that ages an intent out into a terminal
state, plus provider reconciliation that asks the provider what actually happened
(SDD §21.4, §24.1). **Neither is in scope.** Until they exist, an intent whose
callback is lost holds its order's only slot forever and recovery is manual — a
support ticket and a hand-written UPDATE. That is the trade, written here so it is
found now and not in month three.

## Consequences

- **Split payments are out.** One live intent per order, combined with the exact-amount
  rule in the design's §2.4, means an order cannot be collected in two pieces. That is
  a deliberate v1 narrowing, and it is what makes overpayment *structurally*
  impossible rather than merely CHECK-constrained.
- **`orders.PARTIALLY_PAID` stays reachable, but only one way.** It becomes reachable
  through a *partial capture* of a single authorized intent, never through a second
  intent. The two mechanisms look similar from the outside and are not the same; only
  one of them is being built.
- **Releasing the slot is announced, not silent.** Because cancellation is the mechanism
  that frees an order to be collected again, `CancelPaymentIntentService` emits
  `payment.cancelled` in the same transaction as the transition, carrying
  `previousStatus`. The design spec originally named `payment.created` as the only event;
  that was a gap rather than a decision, and it is corrected in §2.4. A consumer fed only
  the creation event would hold a permanently live intent in its read model and never learn
  the slot had been released — the same reasoning that put `payment_state_history` in `V8`
  instead of at PR 4. A stream with a hole in it is worse than no stream, because it looks
  complete. **The rule generalizes to the remaining PRs**: a transition that changes what a
  consumer would believe gets an event, in the transition's own transaction.
- The application pre-check and the index's `WHERE` clause encode the same rule twice.
  `RELEASED_STATUSES` in `JpaPaymentIntentRepository` is the second copy, and it must
  stay identical to the migration's clause — if they drift, the friendly error answers
  a different question from the one the constraint enforces.
- Retrying `POST /api/v1/payment-intents` after a 409 will never succeed. Unlike most
  409s this one is not transient: the merchant's route forward is to cancel the live
  intent, and the error is what tells them so.
- Every future state added to `PaymentIntentStatus` inherits a decision by default —
  it blocks the slot unless it is added to the exclusion set. That default is the safe
  one, and it means a new terminal state is a change to `V8`'s index as well as to the
  enum.
- `uq_payment_intents_live_per_order` is also the index the `?orderId=` filter and the
  create path's lookup would want, but `idx_payment_intents_merchant_order` exists
  separately: a partial index cannot serve a query over rows it excludes.

## Deliberately out of scope

- **A `PROCESSING` timeout sweeper**, per §5. It is the missing half of that deferral,
  not an enhancement.
- **Provider reconciliation** (SDD §24.1). It is what would resolve both §4's race and
  §5's lost callback, and it needs a provider to reconcile against.
- **Multiple concurrent intents per order**, and therefore split and partial payments.
  Revisiting this needs the Ledger, because the running total it requires has to be
  correct somewhere authoritative.
- **An operational alert on intents stuck in `PROCESSING`.** It measures a sweeper.
