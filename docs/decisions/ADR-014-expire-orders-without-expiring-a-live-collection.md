# ADR-014: Expire orders on a timer, but never one that has a live payment intent

## Status

Accepted

> **Why this is its own ADR rather than a section of ADR-013.** ADR-013 is about an order moving out
> from under an intent *because a merchant asked it to*. This is the same collision reached by a
> **timer with no caller**, and it is answered with different machinery — a cross-module port and a
> row lock the sweeper takes itself, not a re-read inside a merchant's transaction. It also has to
> settle a question ADR-013 never faced: **who is allowed to ask whether an order is being paid**,
> when the asker is Order and the answer lives in Payment.
>
> ADR-015 (the `PROCESSING` timeout) is its sibling and lands in the same PR. The two compose, and
> §6 says how.

## Context

`orders.expires_at` and `OrderStatus.EXPIRED` have existed since `V5`. **Nothing has ever set the
status.** `V5` said so in a comment and open item 10 has carried it since:

> `-- When the order stops being payable. Optional. NOTHING SWEEPS THIS YET.`

So a merchant can set a deadline, the deadline passes, and the order stays `PENDING` forever — a
promise the API makes and does not keep. Building the sweeper is not the hard part. **Not
reintroducing open item 1 while building it is.**

### The collision

An order holds at most one live payment intent (ADR-011), and the slot is released only by `FAILED`
or `CANCELLED`. Suppose the sweeper expires an order that has a live intent:

- The intent is still live and still holds the slot, so a second create is refused `409`.
- The intent cannot be confirmed: confirm re-reads payability and an `EXPIRED` order is not payable
  (ADR-013 §1).
- From this PR it cannot be captured either, for the same reason.
- The order cannot be un-expired. There is no route.

**That is exactly the shape of open item 1** — an order dead in both directions with a live intent
attached — arrived at from the other side, and by a background job rather than a deliberate merchant
action. A merchant who cancels an order at least knows they did it. Nobody asked for this one.

### The constraint that makes it hard

The obvious guard is "skip orders that have a live intent". But **Order must never learn that
Payment exists** (design spec §0.5), and `ModuleBoundaryTest.orderNeverImportsPayment` has an *empty*
allowlist — no exceptions at all, unlike the two Payment → Order adapters. The sweeper is Order's own
code acting on Order's own table, and it needs a fact only Payment has.

Four candidates, all with costs:

| Candidate | Closes it? | Cost |
|---|---|---|
| **A.** Skip orders with any non-terminal intent | Yes | Order needs a read it cannot do — *unless the port is inverted, see §2* |
| **B.** Expire only orders that never got an intent | Yes, more bluntly | Needs the *same* read. "Never got one" is no easier to answer than "has a live one", and it is strictly worse: an order whose intent failed would never expire |
| **C.** Make expiry advisory — refuse payment past `expires_at`, never set the status | No | Leaves open item 10 open. The status is what the merchant sees, and `EXPIRED` stays unreachable |
| **D.** Expire anyway; treat the stranded intent as ADR-013's accepted residue | No | Reintroduces open item 1, silently and on a timer. Rejected |

**B is the trap worth naming.** It reads like the cheap option — "only sweep orders nothing ever
touched" — and it needs precisely the same cross-module read as A while producing a worse rule.

## Decision

### 1. The sweeper skips any order that holds a live payment intent

Candidate **A**. `ExpireOrdersService` asks, per candidate order, and skips if the answer is yes. The
order is not expired, not marked, not deferred to a dead-letter list — it is simply left `PENDING`
and reconsidered on the next sweep.

"Live" is **Payment's** definition, not a copy of it: the adapter delegates to the same
`existsLiveForOrder` the create path's slot pre-check uses, whose released-status set
(`FAILED`, `CANCELLED`) is held in one place and must match
`uq_payment_intents_live_per_order`. So *an intent that blocks a second create* and *an intent that
blocks an expiry* are one predicate by construction, and cannot drift into disagreeing about the same
order.

### 2. Order declares the port; **Payment** implements it, so the graph stays acyclic

This is the part that took the thinking. The naive shape is an adapter under
`order/infrastructure/payment` that imports Payment and is allowlisted in `ModuleBoundaryTest`,
matching how Order reads Customer and Payment reads Order. **It would have made the dependency
cyclic** — Order → Payment → Order — and neither module extractable without the other, which is the
one thing ADR-001's "modular monolith, extract later" plan cannot afford.

Instead:

- `com.paymesh.order.application.PaymentActivityLookup` — a one-method interface **Order owns**, in
  Order's own vocabulary (`hasLivePaymentIntent(merchantId, orderId) → boolean`). No Payment type
  appears on it; the parameters are a `MerchantId`, a `String` and a `boolean`.
- `com.paymesh.payment.infrastructure.order.PaymentActivityAdapter` implements it, next to
  `OrderModuleLookup`, on the side that is **already** allowed to import the other.
- `PaymentConfiguration` registers the bean. `OrderConfiguration` injects it *by Order's own type*
  and never names a Payment class.

Result: `ModuleBoundaryTest.orderNeverImportsPayment` keeps its empty allowlist, and the arrow still
points one way. Dependency inversion doing the job it exists for.

**The bean is required, not optional.** If Payment is extracted and nothing implements the port, the
context fails to start. That is correct: a sweeper that cannot ask the question must not run, and a
silently-absent implementation would expire live orders in production while every test in the Order
module stayed green.

### 3. The check is a check; the **order row lock** is what closes the race

`hasLivePaymentIntent` is a read across a module boundary and cannot be anything else. Between the
answer and the `UPDATE`, Payment could create an intent.

**The lock closes it, and it works because both paths lock the same row.** Per candidate, in its own
transaction, the sweeper:

1. re-reads the order with `SELECT ... FOR UPDATE` (`GetOrderService.getByIdForUpdate` — the method
   ADR-013 §4 added for Payment's create path),
2. re-checks eligibility on the aggregate (`hasExpiredBy`),
3. asks the live-intent question,
4. writes.

Payment's create path takes `OrderLookup.findForUpdate` on **that same order row** before inserting
an intent (ADR-013 §3). So the two serialize:

- Payment wins → the sweeper waits, then sees its intent, and skips.
- The sweeper wins → Payment waits, then its payability re-read sees `EXPIRED` and answers
  `422 ORDER_NOT_PAYABLE`.

Lock ordering stays `orders` before `payment_intents`, as ADR-013 §3 requires. Nothing here takes
them the other way, so there is still no cycle and no deadlock.

### 4. One transaction per order, not one per batch

A batch-wide transaction would hold locks on every candidate for the length of the sweep, blocking
merchants creating intents against orders the sweep may not even touch, and one failure would roll
back the orders already expired. Per-order transactions cost more round trips and are worth it.

One bad row is caught, counted and logged rather than allowed to abort the pass — candidates come
back oldest-deadline-first, so a permanently failing order would otherwise sit at the head of every
batch and disable expiry platform-wide.

### 5. Batched, idempotent, tenant-agnostic and tenant-safe

- **Batched** (`batch-size`, default 200) so a backlog drains over several runs.
- **Idempotent** because of step 2: the second run finds `EXPIRED`, is not eligible, and returns
  before any write. No second history row, no second event. The candidate query's `status = 'PENDING'`
  filter is a performance detail; the re-check under the lock is the guarantee.
- **Tenant-agnostic**: `OrderRepository.findExpirable` is the one read in that port with no
  `MerchantId`. A timer has no token, and having the scheduler invent a tenant is precisely what
  ADR-007 forbids.
- **Tenant-safe**: the merchant is read *off each candidate row*, and every write that follows — the
  locking re-read, the live-intent question, the history row, the event — is scoped by it.
  `idx_orders_expirable` is consequently the first index on `orders` that does not lead with
  `merchant_id`, and `V11` says why at the point of definition.

### 6. The transition is `SYSTEM`, and it writes history and an event like every other

`PENDING → EXPIRED`, one `order_state_history` row and one `order.expired`, in the transition's own
transaction. The actor is `SYSTEM` with a **null** `actor_id`: there is no principal behind a timer,
and naming the merchant would claim the merchant asked for this.

`ck_order_state_history_actor` admits `MERCHANT` and `SYSTEM` only — no `PROVIDER`, because a
provider never touches an order (§0.5). That is the one place `order_state_history` deliberately
does not mirror `payment_state_history`.

### 7. Configurable, and **off under the `dev` profile**

`paymesh.orders.expiry-sweep.{enabled,interval,batch-size}`, defaulting to on / 5m / 200 in
`application.yaml`. `application-dev.yaml` sets `enabled: false`, and that single line is why the
suite is not flaky: every `@SpringBootTest` runs `@ActiveProfiles("dev")`, and a timer mutating orders
mid-assertion fails rarely, on someone else's change, unreproducibly.

`OrderExpirySweeper` is a `@Scheduled` bean and holds **one call and one log line**. Every rule above
lives in `ExpireOrdersService`, a plain `final` class taking an injected `Clock` — which is why
`ExpireOrdersServiceTest` can exercise all of it with no scheduler and no database.

## Consequences

### The residue, stated rather than discovered later

- **Expiry is deferred, indefinitely, while a collection is live.** An order past its deadline with a
  live intent stays `PENDING` for as long as that intent stays live — and an intent stuck in
  `PROCESSING` used to stay live forever. **ADR-015 is what bounds this**: the `PROCESSING` timeout
  fails the intent, which releases the slot, and the next sweep expires the order. The two open items
  turned out to solve each other, and neither is complete alone. Without ADR-015 this decision would
  be trading one indefinite hang for another.
- **`expires_at` therefore does not mean "will be `EXPIRED` at that instant".** It means "will be
  expired at or after that instant, once nothing is collecting against it". A merchant reading the
  API sees an order past its deadline still `PENDING` and has to know why. That is a real
  documentation cost and it is the price of not killing live collections.
- **The window is closed for `create`, not for `confirm`.** Confirm deliberately takes a plain read
  rather than an order lock (ADR-013 §2), so a sweep committing between confirm's payability read and
  confirm's commit leaves a `PROCESSING` intent against an `EXPIRED` order. This is the *same* window
  ADR-013 already accepted for cancel, reached by a new writer — not a new one. It is bounded by the
  same fact: no money moves without a provider callback.
- **Order now has a required bean it cannot itself provide.** The compile-time graph is acyclic; the
  *runtime* graph is not independently deployable. Extracting Payment means supplying a real
  implementation — an HTTP call, or better, an Order-owned read model fed by `payment.created` /
  `payment.cancelled` / `payment.failed`. Noting it now so the extraction does not discover it.
- **A merchant cannot force an expiry.** There is no endpoint; the only route to `EXPIRED` is the
  timer. A merchant who wants an order gone now uses cancel, which is a different status and says
  something different.

### Everything else

- **`OrderStatus.EXPIRED` is reachable, and `orders` gains its first `SYSTEM` actor.** `PAID` and
  `PARTIALLY_PAID` remain unreachable — Payment does not write `orders.status` and the
  `payment.succeeded` consumer does not exist.
- **`order.cancelled` and `order.expired` join the outbox**, and `CancelOrderService` — which had no
  transaction, no history and no event at all — now has all three. Nothing publishes them; there is
  still no relay.
- **`order_state_history` starts at `V11` with no backfill**, and `V11` says so at length. Every
  pre-existing order has no timeline and never will: `from_status`, `actor_id` and `occurred_at` for
  a past cancellation are unknowable, and synthesising them would be inventing an audit trail rather
  than recording one.
- **`Order.cancel` is still unchanged and still unaware.** The candidate this ADR rejected in
  ADR-013's table — "Order refuses to cancel while a live intent exists" — stays rejected, and the
  port added here does **not** reopen it: it is consulted by Order's own sweeper, not by the merchant
  cancel path, and adding it there would put a Payment concern inside a merchant-facing rule.

## Deliberately out of scope

- **Expiring payment intents.** Only orders. An intent's exits are cancel and ADR-015's timeout.
- **A merchant-facing `EXPIRED` filter, endpoint or notification.** Nothing tells the merchant; the
  status is visible on read and that is all.
- **Reconciling orders already past their deadline before this migration.** The first sweep after
  deployment expires all of them at once. On this codebase's data that is nothing; on real data it
  would want a throttle, and `batch-size` is the knob.
- **Distributed scheduling.** Two instances both running the sweep is *safe* — each order is decided
  under its own row lock and re-checked before any write — but it is wasted work. A leader election
  or an advisory lock is the answer when there are two instances, and there is one.
