# ADR-016: Deliver domain events in-process, on a broker-shaped consumer contract, before Kafka

## Status

Accepted

> **Why this is its own ADR rather than an amendment to ADR-010.** ADR-010 decided *where an event is
> written* and deliberately stopped there: "There is no Kafka, no relay, no publisher, and no
> `processed_events` inbox. Every row written stays unpublished forever." This decides *how it is
> delivered*, which is a different question with a different set of rejected alternatives — and it is
> the change that ends the safe state ADR-010 named. ADR-010's §3 is superseded by this document;
> the rest of it stands unchanged.

## Context

`outbox_events` has existed since ADR-010 and **nothing has ever read it**. The consequences were
written down at the time and have been carried as open items ever since:

- Open item 14 in `docs/project-status.md`: *"The outbox has no relay, so nothing is ever
  published."*
- §7 of `docs/project-walkthrough.md`, "the mailbox with no postman", whose closing line is
  *"anyone reading the API without this context will file it as a bug."*
- `OrderStatus`'s own javadoc: `PAID` and `PARTIALLY_PAID` *"are still not [reachable], and will not
  be until the outbox has a relay and Order has a consumer of `payment.succeeded`."*

So a merchant can create an order, collect the full amount against it, watch the payment intent
reach `SUCCEEDED` — and read the order back as `PENDING`. The money side is right and the
obligation side never moves, because Payment must not write the `orders` table (design spec §0.5)
and nothing else was listening.

Two further things wait on delivery rather than merely wanting it. The **Ledger** is the next
capability in Phase 1 and it is a consumer of `payment.succeeded` by construction — it cannot be
built against a table nobody reads. And the whole modular-monolith bet in ADR-001 is that the
**contracts** get proven before anything is extracted; an event contract that has never been
consumed is not proven, it is merely written down.

### The thing that had to be decided first

The obvious reading of the SDD is "add Kafka" — §22.2 specifies topics and partition keys, §22.3 the
outbox, §22.4 the inbox. But ADR-001 says services are extracted only *after* the contracts are
proven, and today there is exactly one process. A broker between two packages in one JVM adds a
running dependency, a serialization format, a consumer-group model, an offset store and a whole
class of operational failure — and buys nothing that a method call does not already give, because
there is no network between producer and consumer to be unreliable.

The trap is that the *easy* in-process design is also the one that has to be thrown away. A Spring
`ApplicationEventPublisher` listener takes a typed object and runs in the publisher's transaction;
that is a fundamentally different contract from a broker's, so every consumer written against it
would be rewritten on the day Kafka arrives — which is exactly when the system can least afford it.

## Decision

### 1. Delivery is in-process and synchronous. The CONSUMER CONTRACT is the one a broker needs

`EventDispatcher` holds handlers indexed by event type and calls them directly. There is no broker,
no queue, no thread pool and no serialization.

But `EventHandler` is shaped for a broker, not for a method call:

```java
public interface EventHandler {
    String consumerName();          // the inbox key
    String eventType();             // what it subscribes to
    void handle(OutboxEvent event); // an ENVELOPE, with a Map payload
}
```

Three properties, each chosen because a Kafka consumer would need it:

- **An envelope in, not typed arguments.** `OutboxEvent` is the SDD §22.1 envelope and its payload is
  `Map<String, Object>` — what actually crosses a wire. Untyped access is mildly awkward and that is
  the price of the contract not changing later.
- **Deduplication through `processed_events`, keyed `(consumer_name, event_id)`.** A broker gives
  at-least-once and so does this relay (§3), so the inbox is not optional scaffolding for a future
  transport; it is load-bearing today.
- **Handlers must be idempotent and must throw to retry.** Both are broker semantics. Neither is
  natural for a method call, and both are required here.

**Swapping `EventDispatcher` for a Kafka listener changes no implementation of this interface.** That
is the whole claim, and it is what makes the in-process choice a stepping stone rather than a detour.

A useful side effect: forcing a `Map` payload is *also* what lets Order consume a Payment event while
`ModuleBoundaryTest.orderNeverImportsPayment` keeps its empty allowlist (§5).

### 2. Rejected alternatives

| Candidate | Why not |
|---|---|
| **A. Kafka now** (SDD §22.2) | An operational dependency, a schema registry decision and a consumer-group model, to move a message between two packages in one JVM. ADR-001 says extract after the contracts are proven, and nothing has consumed an event yet, so there is no proven contract to distribute. The relay and inbox built here are exactly the two pieces Kafka needs anyway; only the transport changes. |
| **B. Spring `ApplicationEventPublisher`** | Free, and wrong. It hands a typed object to a listener in the publisher's own transaction, so there is no envelope, no dedup and no retry — a consumer written against it assumes ordering and exactly-once, and every one would be rewritten for Kafka. It also couples consumer to producer types, which is the module boundary this project spends `ModuleBoundaryTest` to keep. |
| **C. Call the consumer directly from the producer** | `CapturePaymentIntentService` calling an Order service. Smallest diff, and it deletes the boundary: Payment would write `orders.status` through a call, which design spec §0.5 forbids and `ModuleBoundaryTest` would not catch, because the arrow points the allowed way. The outbox row would become decorative. |
| **D. A database trigger on `payment_intents`** | Moves business logic to where no reader looks and no test runs, and makes the transition invisible to the state-history and event trail this codebase keeps for every other transition. |
| **E. Relay with no inbox**, relying on each aggregate's own state guard | Tempting, because `Order.markPaid` already refuses a non-`PENDING` order, so a duplicate is *already* absorbed. Rejected because that is an accident of Order's state machine, not a property of delivery: the Ledger posts *entries*, which have no "already done" state to check — a second delivery is a second entry and the balance is wrong forever. The inbox is what makes at-least-once safe for a consumer that does not happen to be idempotent by nature. |

### 3. The relay: bounded batch, per-item isolation, and mapping inside the catch

`PublishOutboxEventsService.publish()` claims
`outbox_events WHERE published_at IS NULL ORDER BY occurred_at ASC LIMIT n` — the query
`idx_outbox_events_unpublished` was built partial for, which V7 already calls "THE RELAY'S CLAIM
QUERY". For each row it maps, dispatches, and stamps `published_at`.

**Mapping happens inside the per-item `try/catch`, and this is the most deliberate line in the
class.** Open item 2 describes the bug both existing sweeps have: they map every candidate through
the aggregate *inside the repository call*, which is outside the catch, so one unmappable row throws
out of the whole pass — and because the ordering is oldest-first, it sits at the head of every
subsequent batch and disables the job permanently and silently. So `OutboxReader.findUnpublished`
returns raw, unvalidated `UnpublishedEvent` rows and `toEvent()` is called per item inside the try.

**A failed event blocks its own aggregate for the rest of the pass, and nothing else.** Without that,
the first failure would deliver an aggregate's *second* event after its first one failed — a
payment's outcome before the payment, which is worse than delivering neither. Four lines: a set of
aggregate ids that failed this pass.

### 4. Transaction boundaries

| Boundary | Owner | Covers |
|---|---|---|
| one per **(handler, event)** | `EventDispatcher` | the `processed_events` claim **and** everything that handler writes |
| one per event, **after** dispatch returns | the relay | the `published_at` stamp only |

**Per handler, not per event**, and that is the point of the inbox. One transaction spanning every
consumer would mean the Ledger failing rolls back Order's committed work — and rolls back the inbox
row recording it, so both re-apply. Per handler, Order stays processed and only the Ledger retries.
Consumers of one event are independent by construction, which is the property that lets them become
separate services.

**The stamp commits separately, and that is what makes delivery at-least-once.** A crash between a
handler's commit and the stamp redelivers the event; every consumer's inbox row makes that a no-op.
It is not a defect to remove — merging the two transactions would only move the window, because a
consumer that will one day live in another process cannot share a transaction with the relay at all.

**A handler must never open its own transaction.** `ApplyPaymentSucceededService` is consequently the
one application service in this codebase with no `TransactionTemplate` argument, which is stated in
its javadoc and visible in `OrderConfiguration`'s `@Bean` method.

### 5. Order consumes `payment.succeeded`, and still does not know Payment exists

`PaymentSucceededHandler` (in `order.infrastructure.events`) reads `orderId`, `capturedAmountMinor`
and `occurredAt` out of the `Map` and calls `ApplyPaymentSucceededService`, which takes a
`MerchantId`, an `OrderId`, a `long` and an `Instant`. `Order.markPaid` decides:

- `capturedAmountMinor == order.amountMinor` → `PAID`
- `0 < capturedAmountMinor < order.amountMinor` → `PARTIALLY_PAID`

**The comparison is against the ORDER's amount, never the payload's.** The order states the
obligation; a payment intent states an attempt to meet it. The two figures agree today, so reading
the payload's would pass every test that did not check — and would mark an order fully paid on the
strength of a document that is not the obligation.

`PAID` and `PARTIALLY_PAID` are reachable for the first time since V5 declared them.

The merchant is taken from the **envelope**, not the payload: the envelope's was copied from the
aggregate by the producer and can never be a caller's.

`ModuleBoundaryTest` keeps its empty allowlist and gains a second test,
`orderConsumesPaymentEventsWithoutNamingPaymentTypes`, because the first one would also pass if the
consumer were simply deleted and Payment quietly wrote `orders.status` instead.

### 6. `payment.succeeded` had two payload shapes at one version, and this fixes it

Found while writing the consumer. The event is emitted from two places and the payloads disagreed:

| key | `CapturePaymentIntentService` | `RecordProviderCallbackService` |
|---|---|---|
| `paymentIntentId`, `merchantId`, `orderId`, `amountMinor`, `capturedAmountMinor`, `currency`, `previousStatus`, `status` | yes | yes |
| `customerId`, `captureMethod` | **yes** | no |
| `capturedAt` | **yes** | no |
| `occurredAt` | no | **yes** |

Same name, same `version: 1`, nothing in the envelope to distinguish them. A consumer reading
`customerId` would get a value from one authority and `null` from the other.

**One shape from both**, being the union: `customerId` and `captureMethod` are **added** to the
provider path rather than removed from the capture path, because both are facts about the intent that
that method already holds, and dropping data a consumer might need is the worse half of the trade.
They land on all four provider outcomes, not just `payment.succeeded` — a uniform shape across one
aggregate's events beats a minimal one.

`capturedAt` is **removed** and `occurredAt` carries its value. That is the one key deleted, and it
loses no data: on the capture path the two were the same `Instant` by construction. The surviving key
now means the same thing on both paths — *when the authority that decided this says it happened*: the
provider's clock there, the capture instant here. The envelope's own `occurredAt` continues to mean
when PayMesh recorded it, and a late delivery makes those genuinely different facts.

**The version stays 1, and that is an argument rather than an oversight.** Bumping to 2 would claim
there is a v1 consumer to protect, and there has never been one — no event has ever been delivered to
anything, which is the entire premise of this ADR. This is the last moment the divergence can be
fixed for free. After this branch a consumer exists and the same edit becomes a versioned migration
with a compatibility window.

### 7. Configuration, and **off under the `dev` profile**

`paymesh.events.outbox-relay.{enabled,interval,batch-size}`, defaulting to on / 2s / 100 in
`application.yaml`. `application-dev.yaml` sets `enabled: false`, for the reason both sweeps are off
there and more sharply: `dev` is the profile every `@SpringBootTest` runs under, and a timer that
moves an order to `PAID` between a test's capture and its assertion is a flake nobody can reproduce.

`interval: 2s`, not the sweeps' `5m`, and the difference is the point. A sweep is catch-up work
nobody is watching. This is **delivery latency** — the gap between a payment succeeding and the
merchant's order reading `PAID` — and five minutes of that is indistinguishable, from the API, from
the bug this change fixes.

`OutboxRelay` is a `@Scheduled` bean holding one call and one log line; every rule lives in
`PublishOutboxEventsService`, a plain `final` class with an injected `Clock`, which is why the
integration tests call `publish()` directly and never wait for a scheduler.

### 8. `processed_events` carries no merchant and no foreign key

Both are deliberate and both are argued at length in `V14`'s header. Briefly: the dedup identity is
the event, platform-wide (V7 says why at the point `event_id` is defined), so a `merchant_id` here
would be data no query reads and a `merchant_id` *in the key* would let one event be applied once per
tenant. And a foreign key to `outbox_events` would have to be migrated away the moment the producer
lives in another database — SDD §22.4 defines the inbox on the consuming side for exactly that
reason.

## Consequences

### The accepted costs, stated plainly

- **An event that fails forever freezes its own aggregate forever.** It is retried at the head of
  every pass, fails again, and its successors are deferred again. The platform still drains — every
  other aggregate is unaffected — but that one makes no progress, and the only signal is a WARN per
  pass and a growing `min(occurred_at) where published_at is null`. **There is no dead-letter table,
  no attempt counter and no alert.** The alert is SDD §24's "oldest unpublished event age" and
  belongs with observability, which does not exist. This is the largest known hole in this change.
- **`occurred_at` is not unique, so two events for one aggregate at the same instant have no defined
  order.** Inherited from the same trade ADR-012 accepts for provider callbacks, and with the same
  proper fix: a monotonic sequence rather than a timestamp. No path produces two such events today.
- **One relay instance.** Two would be *safe* — the inbox primary key arbitrates and the claim is
  `INSERT … ON CONFLICT DO NOTHING` — but they would duplicate work. `FOR UPDATE SKIP LOCKED` on the
  claim query is the upgrade path, and it is an efficiency change rather than a correctness one.
- **Neither `outbox_events` nor `processed_events` is ever pruned.** Both grow with the event log.
  `occurred_at` and `processed_at` are the columns a reaper would sweep, and the partial index
  already keeps the *backlog* index small now that a relay keeps up.
- **An order reaches `PAID` some seconds after its payment succeeds, not instantly.** `expires_at`
  already taught this lesson (ADR-014): a merchant reading the API immediately after a capture may
  still see `PENDING`. Two seconds rather than five minutes, but not zero, and a merchant polling
  tightly will notice.
- **Order now has a bean that consumes a Payment event.** The compile-time graph is still acyclic and
  the allowlist is still empty, but extracting Order means the consumer needs a real subscription to
  a real topic. That is the point of the contract, and it is worth noting it is not free.

### The rest

- **`OrderStatus.PAID` and `PARTIALLY_PAID` are reachable**, and `orders.amount_paid_minor` moves for
  the first time. Every status in the enum and in `ck_orders_status` now has a code path.
- **`order.paid` and `order.partially_paid` join the outbox**, emitted from inside the consumer's own
  transaction under the rule the Payment PRs settled. Nothing subscribes to them; they are dispatched
  to an empty handler list and stamped published, which is the correct handling of an event nobody
  wants — and is what a reporting read model will attach to.
- **`order_state_history` gains its first consumer-written row**, `SYSTEM` with a null actor. V11
  predicted this exact row three migrations early and refused to add `PROVIDER` to
  `ck_order_state_history_actor` so that the boundary violation would stay unspellable.
- **`OutboxEventJpaEntity` stays `@Immutable` and `published_at` stays unmapped.** Mapping it would
  not work: Hibernate drops an immutable entity from state management, so an assignment would look
  correct and silently do nothing. The relay uses a native claim query (extra columns in a native
  result are ignored) and a native `UPDATE … WHERE published_at IS NULL`, which is also a
  compare-and-swap — a second pass cannot rewrite the first one's delivery time.
- **`V7`'s header is now wrong** where it says "THERE IS NO RELAY YET, AND THAT IS A NAMED SAFE
  STATE", and it is **deliberately left unedited**: Flyway checksums applied migrations, so editing a
  comment in `V7` fails validation on every existing database. A migration is a historical record and
  the correction belongs here and in the docs.
- **Three independent mechanisms now stop a payment being applied twice** — the inbox row, the
  service's `PENDING` re-check, and `Order.markPaid`'s refusal — and none subsumes the others. This
  was *measured*: removing the inbox guard and the service's re-check together still left the
  end-to-end test green, because the aggregate refused. The inbox is therefore proved by a test with
  a guard-free handler that counts invocations, not by the order-level test.

## Deliberately out of scope

- **Kafka, topics and partition keys** (SDD §22.2), per §1 and §2.
- **A dead-letter table, an attempt counter, and the oldest-unpublished-age alert** (SDD §24).
- **Retention for either table.**
- **Distributed scheduling** — leader election or an advisory lock. There is one instance.
- **Any other consumer.** The Ledger is next and is a separate change; `order.created`,
  `payment.created`, `payment.cancelled`, `payment.failed`, `payment.authorized`,
  `payment.requires_action`, `order.cancelled` and `order.expired` are all delivered to nobody, on
  purpose.
- **A merchant-facing view of delivery.** No endpoint exposes `published_at`, the inbox, or the
  backlog.
