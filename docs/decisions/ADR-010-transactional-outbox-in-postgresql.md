# ADR-010: Write domain events to a PostgreSQL outbox, in the caller's transaction, with no relay yet

## Status

Accepted

## Context

The governing invariant of this project is that a request may fail or be retried,
but committed money movement must never be lost, silently duplicated, or become
unauditable. Announcing a state change is part of that: a consumer that never
hears about an order is as wrong as an order that was never written.

The naive shape — commit the state change, then publish — has a window. If the
process dies, the broker is unreachable, or the publish simply throws, the state
change is committed and the event is gone permanently. Reversing the order is
worse: the event is published for a state change that then fails to commit, and
consumers act on something that never happened. A broker write cannot join a
PostgreSQL transaction, so no ordering of the two calls closes the gap.

SDD §22.3 specifies the transactional outbox for exactly this, and SDD §12.6
states the Payment case as an invariant rather than a feature: *payment success
and the `payment.succeeded` outbox row commit in the same database transaction.*

Two things forced this to land as its own change, ahead of Payment:

- `outbox_events` is listed under Order (SDD §11.4) *and* under Payment
  (SDD §12.5). It is one shared table belonging to no capability.
- PayMesh had **no multi-statement transaction anywhere**. Every `@Transactional`
  in the codebase is either Spring Data's own on a CRUD method or the idempotency
  layer's hand-written `@Modifying` query, and every one of them wraps a single
  statement. Introducing the first real transaction boundary inside a PR that also
  introduces a state machine, a provider protocol and four endpoints would bury it.

## Decision

### 1. The event row is written by the caller, inside the caller's transaction

`OutboxWriter.append` performs a plain insert and **assumes a transaction is
already open**. It does not start one, and explicitly does not run
`REQUIRES_NEW` — that would commit the event independently and reintroduce the
window the pattern exists to remove.

The caller opens the transaction. `CreateOrderService` wraps its `save` and its
`append` in one `TransactionTemplate.execute`, and that block is the whole
correctness claim of this change.

### 2. The transaction is opened with `TransactionTemplate`, not `@Transactional`

This is not a style preference. The annotation route does not work here, and the
failure was measured rather than assumed.

Every application service in this codebase is a `final` class with no Spring
annotations, instantiated from an `@Bean` method (CLAUDE.md;
`java-coding-conventions` §13). Spring's transaction advisor needs a proxy, Boot
defaults `spring.aop.proxy-target-class=true` so it reaches for a CGLIB subclass,
and a `final` class cannot be subclassed. Startup fails outright:

```
BeanCreationException: Error creating bean with name '…'
  Could not generate CGLIB subclass of class …
Caused by: java.lang.IllegalArgumentException: Cannot subclass final class …
```

Three bean shapes were tried:

| Bean shape | Boot defaults | with `proxy-target-class=false` |
|---|---|---|
| `final`, no interface — the `CreateOrderService` shape | **startup failure** | still fails |
| `final`, implements an interface | **startup failure**, same `Cannot subclass final class` | works |
| non-final, no interface (control) | works | works |

Adding an interface does not rescue it, because Boot reaches for CGLIB even when
one exists. It works only with `proxy-target-class=false` set platform-wide, which
is not a thing to do to the whole application to fix one bean.

Note what this table does *not* say: `@Transactional` is not "quietly ignored" in
PayMesh. It demonstrably works — just never on an application service, because of
how those beans are shaped.

Dropping `final` and annotating was rejected for a second, independent reason.
This project wires beans by hand so that a dependency is visible in the
constructor. A transaction boundary is the last thing that should be invisible
there: an annotation is a property of the class, while a `TransactionTemplate` in
the constructor is a fact the wiring in `OrderConfiguration` states out loud.

`TransactionTemplate` needs no proxy, works with `final` classes, and is declared
once in `SharedConfiguration` so every transaction shares one policy.

### 3. This change builds the outbox and deliberately stops there

There is no Kafka, no relay, no publisher, and no `processed_events` inbox. Every
row written stays unpublished forever.

That is a named safe state, not an omission. SDD §24 lists it: *"Payment DB
commits; Kafka unavailable → Outbox remains unpublished → relay retries."* An
outbox with no relay is a permanent instance of a documented, non-corrupting
failure mode — the events accumulate, in order, and the relay reads the same table
when it arrives. A publisher interface with one logging implementation would be
scaffolding, and an inbox is a *consumer's* table with no consumers to use it.

Delivery, when it exists, will be at-least-once and never exactly-once. That is
why `event_id` is a public prefixed identifier (`evt_`, ADR-003): it is what a
consumer dedups on.

## Consequences

### The accepted cost, stated plainly

**A service that simply forgets to wrap its two writes compiles, starts, and
passes every happy-path test.** A forgotten `@Transactional` would at least be a
visible annotation someone could grep for; a forgotten `TransactionTemplate` wrap
is nothing at all. Two separate transactions produce exactly the same rows as one
whenever nothing fails — the difference appears only when something does.

This is the price of §2 and it is paid deliberately. The mitigation is a test, not
a hope:

- `OutboxTransactionIntegrationTest.leavesNoOrderRowBehindWhenTheOutboxAppendFails`
  writes the order, then makes `append` throw, then asserts the `orders` table is
  empty. It fails against an unwrapped implementation and was confirmed to fail
  against one before this change was accepted.
- `CreateOrderServiceTest.appendsOrderCreatedInsideTheTransactionThatSavedTheOrder`
  asserts the append happened *inside* the template's callback, at unit speed.
- `OutboxTransactionIntegrationTest.neverUpdatesAnEventItHasAlreadyAppended` holds
  the append-only guarantee below in place. It exists because the first version of
  that guarantee was asserted by a comment and nothing else, which the build did
  not notice.

**Any new producer needs the same pair of tests.** A reviewer of a future capability
that emits an event should treat the absence of a rollback test as the defect,
because nothing else in the build will notice.

### The rest

- `TransactionTemplate` is a Spring type in the `application` layer. That is a
  deliberate, bounded exception to `java-coding-conventions` §13, which asks that
  framework types not leak *deeply* — the domain stays framework-free, and the
  alternative (a hand-rolled `UnitOfWork` port wrapping one Spring class) is an
  abstraction with exactly one implementation.
- The transaction covers only the two writes. `CustomerLookup.exists` and the
  merchant-reference check run before it: they take no locks, and holding a
  transaction open across them buys nothing.
- `published_at IS NULL` **is** the status model. There is no `status` column, no
  `retry_count`, no `last_error` and no dead-letter column — those belong to a
  relay, and ADR-009 already refused a status that exists only to be ignored.
- `outbox_events.merchant_id` gets a **single-column** foreign key. That is the
  exception to the composite-FK rule rather than a violation of it: the composite
  form exists so a key *carrying* a tenant alongside another tenant-scoped id
  cannot point across tenants (`fk_orders_customer`), and here `merchant_id` **is**
  the tenant column. `aggregate_id` deliberately has no FK at all — its target
  table depends on `aggregate_type`, and polymorphic foreign keys do not exist.
- `aggregate_type` has no CHECK constraint. A CHECK listing every capability's
  aggregates would force whichever capability lands next to migrate a shared
  table, which is the coupling a shared table exists to avoid.
- **`OutboxEventJpaEntity` is `@Immutable`, and the reason is measured rather than
  aesthetic.** The table is append-only, so Hibernate is told to drop the entity
  from dirty checking entirely. The first attempt instead made the entity
  `Persistable` with `isNew() == true` to force a persist and skip the merge's
  SELECT; a reviewer measured that shape at **5 inserts and 6 UPDATEs** across the
  outbox suite. The cause is the `payload` map: Hibernate snapshots a JSON
  attribute through serialize/deserialize, so `amountMinor` is written as a `Long`
  and read back as an `Integer`, `Long.equals(Integer)` is false, and every row
  looks dirty on every flush. `@Immutable` measures at 5 inserts, zero updates.

  What it leaves is worth stating: without `Persistable`, Spring Data sees a
  non-null application-minted id and no `@Version`, so `save` **merges** — a SELECT
  then an INSERT, one extra read per append. A reused `event_id` therefore does not
  collide on the primary key; `@Immutable` makes that a silent no-op instead of an
  overwrite of an event a consumer is about to dedup against. That is the outcome
  worth having, and `event_id` is a random UUID, so this is a correctness argument
  rather than a live risk. Trading the SELECT back for the UPDATEs is not an option.

  **What the no-op costs, since the comparison above only beats it against an
  overwrite.** The shape it replaced would have collided *loudly* on the primary
  key. A silent no-op means a producer bug that re-appends a **different** payload
  under a reused id loses the second event with no error anywhere — the failure the
  relay and a consumer's dedup would never see. That is accepted here because no
  path reuses an id: `EventId.generate()` is called per `append`, a retry re-enters
  the producer and mints a fresh one, and the alternative failed the whole money-
  moving transaction to protect against it. The upgrade path, if a second producer
  ever makes id reuse plausible, is `EntityManager.persist` in `JpaOutboxWriter`
  instead of `repository.save`: it keeps `@Immutable`, drops the merge's SELECT, and
  restores the loud primary-key collision. It is not done now because there is one
  producer and nothing to protect against yet.
- The outbox lives in `com.paymesh.shared.outbox` for the same reason the
  idempotency layer does: it governs every capability and belongs to none. Built
  inside `com.paymesh.payment` it would be platform code shaped to one caller.
- `order.created` is emitted from now on and nothing consumes it. `order.paid` is
  **not** emitted, because nothing reaches `PAID`.
- Creating an order now costs one extra INSERT on the write path.

## Deliberately out of scope

- **The relay, Kafka and any publisher**, per §3.
- **The inbox (`processed_events`, SDD §22.4)** — a consumer's table, and there is
  no delivery to consume.
- **An "oldest unpublished outbox age" alert** (SDD §24). It measures a relay.
- **Topic and partition-key mapping** (SDD §22.2). Publishing decides those.
- **Retention.** The table is append-only and nothing prunes it. `occurred_at` is
  the column a sweeper will use, and the partial index already keeps the backlog
  index small once a relay keeps up.
