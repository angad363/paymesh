# ADR-013: Re-read the order's payability at confirm, and lock it at create

## Status

Accepted

> **Why this is its own ADR rather than a section of ADR-011.** ADR-011 is about the *slot*: at
> most one live intent per order, and what it costs when that slot cannot be released. This is the
> mirror direction — the order moving out from under an intent that already holds the slot — and it
> is answered with different machinery (a re-read and a row lock, not an index). ADR-011 §3 already
> names the two directions as distinct problems that "neither subsumes the other". Folding this in
> would have made a document about one invariant carry two, and a future reader citing "ADR-011"
> would have to say which half they meant.
>
> ADR-012 is deliberately skipped here; it belongs to the provider-callback PR.

## Context

*(Found in review of PR 2, 1 August 2026, and recorded as item 3 of the design's §7 and open item 1
of `project-status.md`. Confirmed against PostgreSQL.)*

`Order.cancel` checks only that the order is `PENDING`. It has no idea a payment intent exists, and
**correctly so** — Order must not know Payment exists (design §0.5), because the whole point of the
module boundary is that Order can be extracted without learning what consumes it.

Payment, meanwhile, read `payable` exactly once, *outside* the create transaction, and never looked
again. So this sequence was reachable through the public API with no concurrency at all:

1. `POST /api/v1/orders` → the order is `PENDING`.
2. `POST /api/v1/payment-intents` → an intent is created, `REQUIRES_PAYMENT_METHOD`.
3. `POST /api/v1/orders/{id}/cancel` → the order is `CANCELLED`.

The order is now cancelled and the intent is still live. It still occupies the order's only slot, so
a second create is refused — and refused with `ORDER_NOT_PAYABLE`, which names the wrong problem.
The order is dead in both directions.

The same state was reachable with no cancel at all, as a plain time-of-check to time-of-use race
between the lookup and the insert.

**In PR 2 the damage was an inconsistency, because nothing moved money. From PR 3 it is not.** The
live intent against a cancelled order can be attached, confirmed, and — once callbacks land — reach
`SUCCEEDED`. That is PayMesh collecting for an order the merchant explicitly cancelled, which is the
governing invariant failing: money movement that should never have been committed.

Four candidate answers were on the table, all with costs:

| Candidate | Closes it? | Cost |
|---|---|---|
| Re-read `payable` inside the create transaction | No — narrows the window | Nearly free |
| Payment takes a row lock on the order through the port | The create race, yes | Payment holds a lock on Order's row |
| Order refuses to cancel while a live intent exists | Yes | **Order would have to know Payment exists.** §0.5 forbids it |
| Accept it and reconcile afterwards | No | Needs a reconciler that does not exist |

## Decision

### 1. Confirm re-reads the order's payability, inside its own transaction, and refuses

`ConfirmPaymentIntentService` looks the order up again through `OrderLookup` and throws
`OrderNotPayableException` — `422 ORDER_NOT_PAYABLE` — if it is no longer payable.

**Confirm is the guard that matters, because confirm is the money-moving transition.** An intent may
sit live against a cancelled order; that is an inconsistency and it is visible and recoverable. What
must never happen is that it *collects*. Everything before confirm — creating an intent, attaching a
method — writes rows and moves nothing, so guarding those too would buy a slightly earlier error
message at the price of a second place for the rule to live.

The refusal reuses `ORDER_NOT_PAYABLE`, one code for the same three causes create uses (no such
order, not this merchant's, not payable). Splitting them here would reopen the enumeration oracle
that code exists to close.

Attach is deliberately **not** guarded. It commits PayMesh to nothing.

### 2. Confirm's re-read is a plain read, not a locking one, and that leaves a window

Locking the order at confirm as well was implemented and then backed out. It would close the last
few milliseconds — a cancel committing between confirm's read and confirm's commit — at a price that
is not worth it:

- The order row lock **serializes two concurrent confirms**, so the second one no longer collides
  with `uq_payment_attempts_intent_number`. It gets past the attempt insert (it now counts one
  existing attempt and writes number 2) and fails instead on the intent's optimistic `version`,
  which surfaces as a **500** rather than the `409 PAYMENT_ATTEMPT_ALREADY_STARTED` that names the
  actual problem. The design's §3.5 names the unique constraint as the enforcement for exactly this
  case, and the lock quietly takes it out of the path.
- A double-clicked confirm is common. A cancel landing inside a single confirm transaction is rare.
  Trading a clean answer on the common case for a narrower window on the rare one is the wrong way
  round.

**So the window is real and stated: an order cancelled between confirm's payability read and
confirm's commit leaves a `PROCESSING` intent against a `CANCELLED` order.** What bounds it today is
that no money moves — there is no provider, and the intent can go no further without a callback. It
should be revisited when the Provider Simulator lands, and the answer then is more likely to be
ordering the two writes than a wider lock.

### 3. Create takes a row lock on the order and re-checks payability inside its transaction

`CreatePaymentIntentService` now resolves the order with `OrderLookup.findForUpdate` *inside* the
create transaction. `SELECT ... FOR UPDATE` holds the order row until the transaction ends, so a
concurrent cancel either commits first and is seen, or waits and then finds the intent already
there.

This closes the create-side TOCTOU race completely. **It does not stop step 3 of the sequence above**
— an order cancelled a minute after the intent was committed is not a race, and no lock has anything
to hold. That case is what §1 is for. The two are complementary and neither is redundant.

Lock ordering is `orders` before `payment_intents`, in every transaction that takes both. Nothing
takes them the other way round, so there is no cycle and therefore no deadlock.

### 4. The lock is issued by Order, not reached for by Payment

`OrderLookup` gains `findForUpdate`; `OrderModuleLookup` delegates it to a new
`GetOrderService.getByIdForUpdate`, which goes through `OrderRepository.findByOrderIdForUpdate` and a
`@Lock(PESSIMISTIC_WRITE)` query on Order's own Spring Data repository.

**Payment does not import Order's repository or entity to do this**, and `ModuleBoundaryTest`
enforces that. What crosses the boundary is a request to hold a row still — not knowledge of how the
row is stored. Order gains no knowledge of Payment in return: `getByIdForUpdate` says "hold this row
still", never "a payment is being created", so extracting Order later remains a change to
`OrderModuleLookup` and nothing else.

Pessimistic rather than optimistic (`@Version`), per SDD §23.3: optimistic control fails the loser at
flush time and needs an application-level retry to be correct, while the lock makes the second reader
*wait*, read the winner's committed result, and judge itself against the truth. "Wait and then see"
is the only answer to "may this order be collected against" that cannot be stale.

## Consequences

- **An order cancelled under a live intent is still reachable, and is now a dead end rather than a
  hazard.** The intent cannot be confirmed (422) and the order cannot acquire a second intent (409).
  The merchant's route out is to cancel the intent, which releases the slot — available from both
  `REQUIRES_PAYMENT_METHOD` and, from this PR, `REQUIRES_CONFIRMATION`.
- **An intent already in `PROCESSING` when its order is cancelled is not covered by anything here**,
  and cannot be: it is uncancellable by design (ADR-011 §5) and its outcome is the provider's to
  report. This is the same lost-callback hole ADR-011 §5 names, reached by a different route.
- **Payment now holds a lock on a row in `orders`.** That is a real coupling and it is the price of
  the guarantee. It is bounded: two short transactions, always in the same lock order, always through
  the port. If Order is ever extracted into its own service the lock cannot cross the wire, and the
  answer then is the one this ADR rejected today for lack of a reconciler — accept the window and
  reconcile. Noting that now so the extraction does not discover it.
- **`Order.cancel` is unchanged and stays unaware.** No test, no import and no column in `orders`
  knows what a payment intent is.
- **The guard costs one extra read per confirm.** It is a primary-key lookup on a row the
  transaction is about to depend on, which is the cheapest kind of query there is.
- A future `payment.succeeded` consumer in Order will move `orders.status`, and at that point Order
  is *told* about payments rather than asked. Nothing here conflicts with that: the coupling this ADR
  adds runs Payment → Order, and the consumer runs the other way, asynchronously.

## Deliberately out of scope

- **Refusing to cancel an order that has a live intent.** It closes the hole at the source and it
  would require Order to know Payment exists, which §0.5 forbids and which would make the modules
  mutually dependent. If it is ever wanted, the shape is an Order-side consumer of `payment.created`
  / `payment.cancelled` maintaining a flag Order owns — not a call into Payment.
- **Reconciling the orders that are already in this state.** Nothing sweeps them, and the same
  reaper that ADR-011 §5 and open item 6 both want is what would.
- **A guard on attach**, per §1.
