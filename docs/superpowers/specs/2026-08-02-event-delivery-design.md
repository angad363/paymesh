# Event delivery: the outbox relay, an in-process dispatcher, the inbox, and Order's first consumer

_Written 2 August 2026, before any code. Workstream B of Phase 1. Migration number `V14` is
pre-assigned to this branch; `V13` belongs to another workstream._

This is the change that makes `outbox_events` mean something. Today every capability writes an
event row in the same transaction as its state change (ADR-010) and **nothing ever reads the
table**. `orders.status` therefore never reaches `PAID`, which is open item 14 in
`docs/project-status.md` and §7 of `docs/project-walkthrough.md`.

---

## 0. What this change is, in one paragraph

A scheduled relay polls `outbox_events WHERE published_at IS NULL ORDER BY occurred_at`, hands each
event to an **in-process, synchronous dispatcher**, and stamps `published_at`. The dispatcher looks
up handlers by event type and, for each one, opens a transaction, claims the event in a new
`processed_events` inbox table keyed `(consumer_name, event_id)`, and calls the handler inside that
same transaction. The first handler is Order's: on `payment.succeeded` it moves the order to `PAID`
or `PARTIALLY_PAID`, sets `amount_paid_minor`, appends an `order_state_history` row and emits
`order.paid` / `order.partially_paid`.

### 0.1 What this is deliberately NOT

- **Not Kafka.** ADR-001 says extract services only after the contracts are proven, and a broker
  inside one process buys nothing but operational surface. ADR-016 records the decision and the
  cost. The **consumer contract** is nonetheless the one a Kafka consumer needs — an event envelope
  in, `processed_events` dedup, an idempotent handler — so swapping the transport later changes no
  consumer code.
- **Not a dead-letter queue.** A permanently failing event is retried on every pass, logged, and
  does not block the rest of the batch. §5.3 states the residue.
- **Not retention.** Nothing prunes `outbox_events` or `processed_events`. Both are append-only and
  `occurred_at` / `processed_at` are the columns a future reaper sweeps on.
- **Not a relay for another instance.** One process, one timer, no leader election or advisory lock.
  Two instances would be *safe* — the inbox primary key arbitrates — but wasteful.
- **Not an ordering guarantee across passes.** §5.3.
- **Not an "oldest unpublished event age" alert** (SDD §24). It measures a relay that has just
  started existing; the metric belongs with observability, which does not exist.
- **No new endpoint.** The relay is a timer. Nothing merchant-facing changes.

---

## 1. Where the code lives, and why not a new package

Everything platform-side goes in the **existing** `com.paymesh.shared.outbox`, not a new
`com.paymesh.shared.events`.

The alternative was considered and rejected. `shared.outbox` already owns `OutboxEvent` — the SDD
§22.1 envelope, which is exactly what the dispatcher hands a handler — and `outbox_events`, which is
exactly what the relay reads. Splitting delivery into a second package would put the envelope on one
side of a package boundary and its only reader on the other, and would leave `shared.outbox` a
package that can only write. The inbox is the one piece whose name does not match the package, and
that is a smaller cost than the split: an outbox and its inbox are two halves of one pattern
(SDD §22.3 and §22.4 are adjacent sections for the same reason).

```
com.paymesh.shared.outbox
├── application
│   ├── OutboxWriter                  (exists)
│   ├── OutboxReader                  NEW  port: claim unpublished, stamp published
│   ├── UnpublishedEvent              NEW  raw claimed row, not yet validated  (§3.2)
│   ├── EventHandler                  NEW  the consumer contract               (§4)
│   ├── EventDispatcher               NEW  type -> handlers, inbox, transaction (§4)
│   ├── ProcessedEventRepository      NEW  port: the inbox
│   └── PublishOutboxEventsService    NEW  the relay pass, a plain object       (§3)
├── domain
│   ├── EventId, OutboxEvent          (exist, unchanged)
└── infrastructure
    ├── config/OutboxConfiguration    (exists, extended)
    ├── config/OutboxRelayProperties  NEW
    ├── persistence/jpa/…             extended: reader, inbox adapter, entity
    └── schedule/OutboxRelay          NEW  the @Scheduled timer, one call + one log line
```

Order's consumer:

```
com.paymesh.order
├── application/ApplyPaymentSucceededService   NEW  the rules, plain JUnit-testable
├── domain/Order.markPaid(...)                 NEW  intent-revealing transition
├── domain/OrderPaymentNotApplicableException  NEW
└── infrastructure/events/PaymentSucceededHandler  NEW  reads the Map, calls the service
```

The split between the last two is the same split `OrderExpirySweeper` / `ExpireOrdersService`
already uses, for the same reason: **reading an untyped `Map<String,Object>` out of an event
envelope is a transport concern and belongs in `infrastructure`; deciding what a successful payment
does to an order is a rule and belongs in `application`, where it is testable with no Spring, no
database and no event.**

---

## 2. The schema: `V14__create_processed_events.sql`

```sql
CREATE TABLE processed_events (
    consumer_name VARCHAR(100)             NOT NULL,
    event_id      VARCHAR(40)              NOT NULL,
    event_type    VARCHAR(80)              NOT NULL,
    processed_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (consumer_name, event_id)
);
```

Four decisions worth stating, all of which go in the migration's own comments:

1. **The primary key is `(consumer_name, event_id)` and it is the concurrency control, not an
   access path.** The claim is `INSERT … ON CONFLICT DO NOTHING` and the row count is the answer:
   one row inserted means this consumer has not seen the event and must handle it; zero means it
   has. Exactly the shape `idempotency_records` uses (ADR-009, V4), and for the same reason — the
   database picks the winner, there is no read-then-write window.
2. **`consumer_name` leads**, because one event is delivered to every subscribed consumer and each
   must dedup independently. Order consuming `payment.succeeded` must not suppress the Ledger's
   consumption of the same event.
3. **No `merchant_id`, and this is not a violation of the tenant rule.** The dedup identity is the
   event, platform-wide — V7 says so at the point `event_id` is defined: *"an event id that repeated
   across tenants would be processed once per tenant by any consumer that is not itself
   tenant-scoped."* A merchant column here would be data no query reads, and putting it in the key
   would be actively wrong. The tenant lives in the event payload and the envelope, and every write
   a handler performs is scoped by it.
4. **No foreign key to `outbox_events`.** This is a *consumer's* table. The moment the transport is
   a broker, this inbox holds ids for events another service's database produced, and the FK would
   need a migration to drop. SDD §22.4's inbox is defined on the consuming side precisely so it
   survives that move.

No retention, no reaper, no index beyond the primary key — every read and write is
`WHERE consumer_name = ? AND event_id = ?`.

---

## 3. The relay

### 3.1 The pass

`PublishOutboxEventsService.publish()` — a plain `final` class with an injected `Clock`, no Spring
annotations, mirroring `ExpireOrdersService`:

1. `reader.findUnpublished(batchSize)` → `List<UnpublishedEvent>`, ordered `occurred_at ASC`. The
   query is native and its predicate is `published_at IS NULL`, which is exactly what
   `idx_outbox_events_unpublished` is partial on.
2. For each row, **inside a per-item `try`**:
   - `row.toEvent()` — this is where `EventId.from`, `MerchantId.from` and `OutboxEvent`'s compact
     constructor validate. **Mapping is inside the catch, deliberately** (§3.3).
   - `dispatcher.dispatch(event)`
   - `reader.markPublished(eventId, now)`
3. Return a `RelayResult(examined, published, failed)` so the timer can log it and a test can assert
   it without reading the database.

### 3.2 `UnpublishedEvent`, and why the port does not return `OutboxEvent`

Open item 2 in `project-status.md` describes the exact bug this shape avoids: both existing sweeps
map every candidate row through the aggregate *inside the repository call*, which is **outside** the
per-item `try/catch`, so one unmappable row throws out of the whole pass, sits at the head of every
batch because the ordering is oldest-first, and silently disables the sweep forever.

The relay must not reproduce that. So `OutboxReader.findUnpublished` returns a record of the **raw
columns** — `String eventId, String merchantId, String aggregateType, String aggregateId,
String eventType, int eventVersion, Map<String,Object> payload, Instant occurredAt` — with no
validation at all, and a `toEvent()` method the relay calls per item inside the `try`. A row with a
malformed `event_id`, a merchant id that will not parse, or a zero version fails one iteration and
nothing else.

`UnpublishedEvent` lives in `application`, not `infrastructure`: it is what the port returns, so the
port's own package must be able to name it. It carries no JPA type.

### 3.3 Failure isolation, and the one thing that is NOT isolated

A `RuntimeException` from any of the three steps is caught, counted, logged at WARN with the event
id, and the pass continues with the next row. `published_at` is not stamped, so the event is
redelivered on the next pass, which is safe because the inbox makes a duplicate a no-op.

**One exception to "continue": subsequent events of the same aggregate are skipped for the rest of
the pass.** The relay keeps a set of aggregate ids that failed in this pass and skips any later row
naming one of them. Four lines, and without it the ordering guarantee in §3.4 is a lie the first
time an event fails.

### 3.4 Ordering

Two events for one aggregate are delivered in `occurred_at` order:

- the claim query orders by `occurred_at ASC`;
- dispatch is synchronous, so event *n* is fully handled and stamped before event *n+1* is read;
- a failure blocks its own aggregate for the rest of the pass (§3.3).

**Stated honestly: this holds within a pass and across passes for a healthy aggregate, but a
permanently poisoned event reorders its aggregate's later events relative to it** — the poison row
is retried first on every pass and fails again, and its successors are skipped again, so the
aggregate makes no progress at all until the poison is fixed. That is head-of-line blocking per
aggregate rather than reordering, which is the correct trade, but it means one bad row can freeze
one aggregate indefinitely. The platform still drains. Upgrade path: a dead-letter column plus an
alert, which is what the SDD §24 metric is for.

`occurred_at` is not unique. Two events for one aggregate stamped at the same instant have no
defined order, and this is inherited from the same trade ADR-012 accepts for provider callbacks: the
proper fix is a monotonic sequence, not a timestamp. No path in this codebase produces two events
for one aggregate at one instant today.

### 3.5 Transactions

| Boundary | Owner | Covers |
|---|---|---|
| one per `(handler, event)` | `EventDispatcher` | the `processed_events` insert **and** everything the handler writes |
| one per event, after dispatch returns | `PublishOutboxEventsService` | the `published_at` stamp only |

**Per-handler, not per-event, and that is the whole point of the inbox.** With one transaction for
all handlers, the Ledger failing would roll back Order's work and both would re-apply. With one per
handler, Order stays processed, only the Ledger retries, and its inbox row is the record of which is
which.

**The stamp is a separate transaction, after.** If it fails, the event is redelivered and every
handler no-ops on its inbox row. Delivery is at-least-once and this is the "at least" — it is not a
defect to remove.

**A handler must never open its own transaction.** It runs inside the dispatcher's, and one it
opened itself would commit independently of the inbox row, so a crash between the two would
double-apply on redelivery. `EventHandler`'s javadoc says this; `ApplyPaymentSucceededService` takes
no `TransactionTemplate`, unlike every other application service in the codebase, and its javadoc
says why.

### 3.6 Scheduling

`OutboxRelay`, in `shared/outbox/infrastructure/schedule`. `@Scheduled(fixedDelay)`, one call and
one log line, copied from `OrderExpirySweeper` down to the `initialDelayString`.

```yaml
paymesh:
  events:
    outbox-relay:
      enabled: true        # application.yaml
      interval: 2s
      batch-size: 100
```

```yaml
paymesh:
  events:
    outbox-relay:
      enabled: false       # application-dev.yaml — NON-NEGOTIABLE
```

The `dev` profile is what `@ActiveProfiles("dev")` activates for every `@SpringBootTest`, and a
timer that moves orders to `PAID` underneath an assertion is a flake generator. The relay is a plain
bean either way, so integration tests call `publish()` directly. `@ConditionalOnProperty` means
"off" removes the bean and therefore the `@Scheduled` registration entirely, rather than ticking a
no-op.

`interval: 2s` rather than the sweepers' `5m`. This is not catch-up work — it is the delivery
latency a merchant sees between a payment succeeding and their order reading `PAID`, and five
minutes of that would be indistinguishable from the bug this change fixes. `fixedDelay` still, so
two passes never overlap.

---

## 4. The dispatch and consumer contract

```java
public interface EventHandler {
    String consumerName();   // the inbox key. Stable forever; renaming it replays history.
    String eventType();      // "payment.succeeded"
    void handle(OutboxEvent event);
}
```

One handler, one event type. A consumer wanting two types registers two handlers — simpler than a
`Set<String>` and it keeps `consumerName` meaningfully scoped.

```java
public final class EventDispatcher {
    public void dispatch(OutboxEvent event) {
        for (EventHandler handler : handlersByType.getOrDefault(event.eventType(), List.of())) {
            transactions.execute(status -> {
                if (processedEvents.markProcessed(
                        handler.consumerName(), event.eventId(), event.eventType(), Instant.now(clock))) {
                    handler.handle(event);
                }
                return null;
            });
        }
    }
}
```

- **An event with no handler is not an error.** `order.created`, `payment.created`,
  `payment.cancelled` and `payment.failed` have no consumer; the relay stamps them published and
  they are done. `published_at` means "delivered to everyone subscribed", which for zero subscribers
  is immediate.
- **`markProcessed` returns `true` when this call claimed the event.** Implementation is
  `INSERT … ON CONFLICT DO NOTHING` and the row count. A concurrent duplicate blocks on the index
  entry until the first transaction resolves, then reads zero — the same arbitration ADR-009 relies
  on.
- **The dedup is per consumer, so adding the Ledger later replays nothing for Order.**
- Handlers are injected as a `List<EventHandler>` and indexed by type at construction. Spring
  supplies the list from the `@Bean` methods each capability's own configuration declares — the
  registration is explicit, in the consumer's own file, and the dispatcher never names a capability.

---

## 5. Order's consumer

### 5.1 The transition, on the aggregate

```java
public Order markPaid(long capturedAmountMinor, Instant paidAt)
```

- refuses unless `status == PENDING`, throwing `OrderPaymentNotApplicableException`;
- refuses `capturedAmountMinor <= 0` or `> amountMinor` (`ck_orders_amount_paid` is the guarantee,
  this is the readable failure);
- **`capturedAmountMinor == amountMinor` → `PAID`; `0 < capturedAmountMinor < amountMinor` →
  `PARTIALLY_PAID`.** The comparison is against **the order's own `amountMinor`**, never the event's.
  A payload's `amountMinor` is the intent's figure and could disagree; the order is the obligation
  and it is the thing being marked;
- sets `amountPaidMinor = capturedAmountMinor` and `updatedAt = paidAt`.

This makes `OrderStatus.PAID` and `PARTIALLY_PAID` reachable for the first time since V5.

### 5.2 The service

`ApplyPaymentSucceededService.apply(MerchantId, OrderId, long capturedAmountMinor, Instant occurredAt)`:

1. `getOrderService.getByIdForUpdate(merchantId, orderId)` — the locking read, for the same reason
   the expiry sweep takes it: a merchant cancelling the order concurrently must serialize with this.
2. If the order is not `PENDING`, return without writing. **This is the aggregate-level idempotency
   guard and it is not redundant with the inbox** — the inbox stops the *same event* being applied
   twice; this stops *a different event* (a redelivery under a new id after a reconciliation, say)
   double-applying. Neither subsumes the other, exactly as ADR-012's three mechanisms do not.
3. `orders.save(order.markPaid(captured, occurredAt))`
4. `history.append(...)` — `SYSTEM`, `actorId` null. There is no principal behind a consumer, and
   `ck_order_state_history_actor` admits only `MERCHANT` and `SYSTEM`. V11 predicted this exact row.
5. `outbox.append(...)` — `order.paid` or `order.partially_paid`, in the same transaction.
   Emitted because this codebase's standing rule (stated in `ExpireOrdersService`'s javadoc, settled
   by the Payment PRs) is that a transition changing what a consumer would believe gets an event in
   the transition's own transaction. Nothing consumes them today; the relay stamps them published
   and moves on.

No `TransactionTemplate`. It joins the dispatcher's (§3.5).

### 5.3 The boundary rule

`ModuleBoundaryTest.orderNeverImportsPayment` has an **empty** allowlist and keeps it. The handler
therefore:

- reads `orderId` and `capturedAmountMinor` out of `Map<String,Object>`;
- imports nothing from `com.paymesh.payment` — not `PaymentIntentStatus`, not `PaymentIntent`;
- takes the merchant from **the envelope's typed `MerchantId`**, not the payload, because the
  envelope's is copied from the aggregate and can never be a caller's;
- lives in `order/infrastructure/events`.

A new test, `orderConsumesPaymentEventsWithoutNamingPaymentTypes` (renamed from this spec's
`…WithoutImportingPayment`, since the import case is already the test above), asserts the handler
file exists and names `payment.succeeded` as a string — so deleting the consumer and quietly
reintroducing a direct call would fail the build rather than merely leaving the allowlist empty. It
also checks that no *code* line carries a fully-qualified `com.paymesh.payment` reference, which the
import-based test cannot see. Comment lines are excluded, because the handler's own javadoc explains
at length why it may not name that package.

**The `Number` trap.** ADR-010 records it: Hibernate round-trips a JSONB map through
serialize/deserialize, so a `Long` written as `capturedAmountMinor` comes back an `Integer`. The
handler must read `((Number) payload.get("capturedAmountMinor")).longValue()`, never cast to `Long`.
A test asserts a value below `Integer.MAX_VALUE` (which is every realistic amount) survives the round
trip.

---

## 6. The contract bug: `payment.succeeded` has two shapes

`payment.succeeded` is emitted from two places, at the same `version 1`, with different payloads:

| key | `CapturePaymentIntentService` | `RecordProviderCallbackService` |
|---|---|---|
| `paymentIntentId`, `merchantId`, `orderId`, `amountMinor`, `capturedAmountMinor`, `currency`, `previousStatus`, `status` | yes | yes |
| `customerId` | **yes** | no |
| `captureMethod` | **yes** | no |
| `capturedAt` | **yes** | no |
| `occurredAt` | no | **yes** |

A consumer reading `payload.get("customerId")` gets a value from one authority and `null` from the
other, with nothing in the envelope to distinguish them. This has never bitten because nothing has
ever read an event.

### 6.1 The fix

**One shape, from both emitters:**

```
paymentIntentId, merchantId, orderId, customerId, amountMinor, capturedAmountMinor,
currency, captureMethod, previousStatus, status, occurredAt
```

- `customerId` and `captureMethod` are added to the provider path. Both are facts about the intent,
  which that path already has in hand.
- `capturedAt` is **removed** from the capture path and its value carried by `occurredAt`. No data is
  lost: on the capture path they were the same `Instant` by construction.
- `occurredAt` means **"when the authority that decided this says it happened"** — the provider's own
  clock on the callback path, the capture instant on the capture path. The envelope's `occurredAt`
  continues to mean "when PayMesh recorded it", and on a late delivery those are genuinely different
  facts, which is why both exist.
- `RecordProviderCallbackService.announcement` serves four event types, so `payment.authorized`,
  `payment.failed` and `payment.requires_action` gain `customerId` and `captureMethod` too. A uniform
  shape across one aggregate's events is worth more than a minimal one.

### 6.2 The version stays 1, and that is an argument, not an oversight

Bumping to 2 would claim there is a v1 consumer to protect. **There has never been one.** No event
has ever been delivered to anything — that is the entire premise of this change — so the shape has no
readers and cannot break any. This is the last moment the divergence can be fixed for free; after
this branch, a `payment.succeeded` consumer exists and the same edit becomes a versioned migration.
Recorded in ADR-016.

---

## 7. Testing

Following `java-coding-conventions` §12: plain JUnit for domain and application, Testcontainers for
anything the database arbitrates, `@ActiveProfiles("dev")` everywhere a context boots, names that
state behaviour.

| Level | Class | What only it can prove |
|---|---|---|
| domain | `OrderTest` (extended) | `markPaid` picks `PAID` vs `PARTIALLY_PAID`, refuses non-`PENDING`, refuses an over-payment |
| application | `PublishOutboxEventsServiceTest` | ordering, the batch bound, one poison row not stopping the pass, the poisoned aggregate being skipped, nothing stamped on failure |
| application | `EventDispatcherTest` | dedup per consumer, an unhandled type being a no-op, the handler running inside the transaction |
| application | `ApplyPaymentSucceededServiceTest` | the amount rules, the timeline row, the event, the non-`PENDING` no-op |
| architecture | `ModuleBoundaryTest` (extended) | Order still never imports Payment |
| integration | `EventDeliveryIntegrationTest` | the loop end to end against PostgreSQL: capture → relay → `orders.status = PAID`; a second delivery changing nothing; the inbox row and the state change committing together; an unmappable row not wedging the pass |

### 7.1 Sabotage, which is the actual requirement

Every invariant test is proved by breaking the implementation and confirming the test goes red. A
green assertion that has never failed is not evidence, and a sabotage that stays green means the
sabotage was unfaithful, not that the code is safe. The PR body carries the table with real output.

| # | Sabotage | Must turn red |
|---|---|---|
| 1 | delete the `processedEvents.markProcessed` guard in `EventDispatcher` | duplicate delivery applies twice |
| 2 | remove the `transactions.execute` wrap in `EventDispatcher` | the inbox row survives a failed handler |
| 3 | move `row.toEvent()` out of the per-item `try` in the relay | one unmappable row stops the whole pass |
| 4 | order the claim query by `event_id` instead of `occurred_at` | delivery order |
| 5 | compare against the payload's `amountMinor` instead of the order's | a partial capture marks the order `PAID` |

---

## 8. Postman

A new folder, **Event delivery (relay → order.PAID)**, running after the existing ones: create
order → intent → attach → confirm → HMAC-signed `SUCCEEDED` callback → poll the order until it reads
`PAID`. It reuses the collection's existing HMAC pre-request script and the `providerCallbackSecret`
collection variable, and mints its own `Idempotency-Key`s and variables so it cannot disturb a
folder above it.

**It needs the relay switched on**, because `spring-boot:run` activates the `dev` profile where it is
off. The folder's description says so, and so do the README and the walkthrough:

```bash
PAYMESH_EVENTS_OUTBOX_RELAY_ENABLED=true ./mvnw spring-boot:run
```

This is the same pattern the two existing sweepers already document.

One existing request has to change: **"The order is still PENDING after a successful capture"** in
the manual-capture folder is now false whenever the relay is running, and racy when it is. It becomes
a request that asserts `200` and a status of `PENDING` **or** `PARTIALLY_PAID`, with the
deterministic proof moved into the new folder where it can poll. Weakening it there is deliberate:
the claim it used to make is the one this change deletes.

---

## 9. Docs to correct

Every place that states the outbox has no relay or that `orders.status` never reaches `PAID`:

- `docs/project-status.md` — open item 14, the Order and Payment capability tables, the SDD coverage
  table's "Events / outbox" and "Order" rows, the ADR table, "What comes next".
- `docs/project-walkthrough.md` — **§7 "the mailbox with no postman" becomes obsolete and is
  rewritten rather than deleted**; §5.2 step 11 ("status is still `PENDING`") and §6.3's "Kafka +
  event delivery" row.
- `README.md` — current status, the data model, the roadmap, and the `PAID`-unreachable claims.
- `docs/decisions/ADR-016-in-process-event-dispatch-before-kafka.md` — new.
- `OrderStatus`'s javadoc, `OrderStateChange.ActorType`'s javadoc, `V7`'s "THERE IS NO RELAY YET"
  block and `OutboxEventJpaEntity`'s "published_at is deliberately unmapped" paragraph are all now
  wrong in the source and are corrected with the code.

---

## 10. Open questions this spec settles, and one it does not

**Settled.** The transaction boundary is per handler; the relay owns the stamp; the version stays 1;
the inbox carries no merchant and no foreign key; the relay lives in `shared.outbox`; a poison event
blocks its own aggregate and nothing else.

**Not settled, and named so it is not discovered later:** an event that fails forever freezes its
aggregate's later events forever, silently apart from a WARN per pass. There is no dead-letter, no
attempt counter and no alert. The condition is visible as a growing gap between
`min(occurred_at) where published_at is null` and now, which is precisely the SDD §24 metric, and
observability is where it belongs.

---

## 11. Corrections made during implementation

_Added after the code landed. This project's method is spec-then-implement **and the spec gets
corrected when it is found wrong** — six things this document said, or failed to say, did not
survive contact with the code._

1. **`published_at` cannot be mapped on the entity, and §3 assumed it could.**
   `OutboxEventJpaEntity` is `@Immutable` (ADR-010 measured why), so Hibernate drops it from state
   management entirely: an assignment to a mapped `publishedAt` would compile, look correct and
   silently do nothing. Both relay queries are therefore **native SQL** — the claim query returns the
   entity, and a native result simply ignores columns the entity does not declare; the stamp is a
   native `UPDATE … WHERE published_at IS NULL`, which is also a compare-and-swap so a second pass
   cannot rewrite the first one's delivery time. `published_at` stays unmapped and the entity javadoc
   now says *why* rather than "there is no relay".

2. **§7.1's sabotage #1 was unfaithful, and finding that out is the most useful thing in this
   section.** "Remove the `processed_events` insert → expect a double-applied order" does **not**
   turn the end-to-end test red. Neither does removing that guard *and*
   `ApplyPaymentSucceededService`'s `PENDING` re-check — measured, both green. **Three** independent
   mechanisms stop a payment being applied twice: the inbox row, that re-check, and
   `Order.markPaid`'s own refusal. Defense in depth is correct, but it means **no order-level
   assertion can isolate the inbox.**

   The inbox is now proved by `callsOneConsumerOnceHoweverManyTimesAnEventIsDispatched`, which
   dispatches one event three times to a handler with no guard of its own and counts invocations —
   the only thing standing between 1 and 3 there is `pk_processed_events`. The order-level test is
   relabelled as what it actually proves: the three mechanisms compose into the right end state.
   This is the same trap `project-status.md` already records for the idempotency filter, where a
   partial sabotage stays green because the database is still arbitrating.

3. **§7.1's sabotage #2 needed a narrower form.** Deleting the `transactions.execute` wrap outright
   turns 8 of 10 integration tests red with `TransactionRequiredException`, which proves the
   transaction is *load-bearing* but not that the inbox row and the state change **commit together**.
   The faithful version keeps a transaction and moves the claim into its own `REQUIRES_NEW` one; that
   turns exactly `rollsBackTheInboxRowWhenTheHandlerFails` red, which is the invariant. Both are
   recorded in the PR.

4. **`V7` must not be edited, and §9 said it would be.** Flyway checksums applied migrations, so
   changing even a comment in `V7` fails validation on every existing database. Its "THERE IS NO
   RELAY YET, AND THAT IS A NAMED SAFE STATE" block is now wrong and is left wrong on purpose; the
   correction lives in ADR-016 and in the docs. A migration is a historical record, not documentation
   to keep current.

5. **Integration tests must drain the relay, not publish once.** The suite runs every
   `@SpringBootTest` against one container, so by the time this class runs there is a backlog from
   every other integration test that ever created an order or an intent. The batch is bounded at 100
   and ordered oldest-first, so a single pass can be consumed entirely by other tests' events. A
   one-pass test passed in isolation and failed in the suite — exactly the flake the `dev`-profile
   rule exists to prevent, arriving by another route. `drain()` loops until a pass publishes nothing,
   which is what a timer does anyway.

6. **§8 understated the Postman change.** "The order is still PENDING after a successful capture" is
   not merely now-false; it is **racy** whenever the relay is on, because it runs milliseconds after
   the capture. It is rewritten to accept `PENDING` *or* `PARTIALLY_PAID`, with the deterministic
   proof moved into the new folder where it can poll. A test that is right only when a background job
   has not yet run is worse than no test.

**One thing the spec got right and is worth keeping:** §3.2's raw `UnpublishedEvent`. Sabotage #3
confirmed the failure mode it exists to prevent, and more sharply than expected — moving `toEvent()`
above the `try` broke the poison-row test *and* a different test in the same class, because the
poison row one test left behind killed another test's entire pass. That is open item 2's pathology,
reproduced live.
