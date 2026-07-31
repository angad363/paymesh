# Design: the transactional outbox and the Payment capability

_Written 1 August 2026. Status: proposed. Supersedes nothing._

Five changes, in dependency order. The first is platform work that Payment cannot be
written correctly without; the remaining four build the Payment Orchestrator (SDD §12)
in slices that are each independently reviewable.

| # | Change | Branch | Migration |
|---|---|---|---|
| 1 | Transactional outbox | `feat(shared)/transactional-outbox` | `V7` |
| 2 | Payment intent core | `feat(payment)/payment-intent-core` | `V8` |
| 3 | Attach method, confirm, attempts | `feat(payment)/confirm-and-attempts` | `V9` |
| 4 | Provider callbacks | `feat(payment)/provider-callbacks` | `V10` |
| 5 | Manual capture | `feat(payment)/manual-capture` | — |

`V6` belongs to the `payment_method_tokens` foreign-key fix and is **not** touched by
anything here. See §0.4 — that fix is deliberately not a dependency of any PR below.

Everything assumes the conventions already in force: package-by-feature (ADR-002),
manual bean wiring, request/domain/response separation, opaque prefixed identifiers
(ADR-003), separate JPA entity and domain aggregate (ADR-004), Testcontainers (ADR-005),
tenancy next to the data (ADR-007), consumer-owned cross-module ports (ADR-008),
idempotency by route registration (ADR-009). Where this document is silent, the existing
**Order** capability is the pattern to copy — Payment is its sibling and should read like
it.

---

## 0. The decisions, up front

The five questions this spec exists to answer, answered before the detail.

### 0.1 The outbox lands **before** Payment, as its own PR

Payment is the first capability where a lost event has financial consequences, and SDD
§12.6 states the requirement as an invariant, not a feature: *payment success and the
`payment.succeeded` outbox row commit in the same database transaction.* That is a claim
about a transaction boundary. Transaction boundaries are the thing a reviewer skims when
they arrive attached to a novel state machine, a new provider protocol and four new
endpoints.

Three further reasons, each sufficient on its own:

- **The outbox is not Payment's.** SDD §11.4 lists `outbox_events` under Order and §12.5
  lists it again under Payment. It is one shared table. Built inside
  `com.paymesh.payment`, it would be platform code sitting in a feature package, shaped
  to one caller, and would have to move.
- **It has a producer that already exists.** `order.created` (SDD §11.5) is specified and
  unemitted. Wiring it makes the outbox PR provable end to end today rather than dead
  scaffolding — which was the stated reason roadmap item 7 deferred the whole thing
  rather than half-building it.
- **It surfaces a blocker early.** See §0.5: this codebase currently has no way to run two
  writes in one transaction, and finding that out inside the Payment PR would stall it.

**What "before" costs.** One extra PR on the critical path, and — because PR 2 emits
`payment.created` — PR 2 cannot merge until PR 1 does. Mitigated by stacking PR 2's
branch on PR 1's, which this project has done before.

**What "with Payment" would have cost.** The reviewer of PR 2 would be judging the
outbox's correctness and the state machine's correctness in one sitting, and the outbox's
entire correctness claim is one `commit()`. The failure mode is not a broken build; it is
a green build in which the state change and the event row commit separately and nobody
notices until an event goes missing under load. That is precisely the class of bug this
project has been paying an independent reviewer to catch.

### 0.2 An order may hold **at most one live payment intent**, and the database enforces it

SDD §11.6 permits the restriction. Take it.

```sql
CREATE UNIQUE INDEX uq_payment_intents_live_per_order
    ON payment_intents (merchant_id, order_id)
    WHERE status NOT IN ('FAILED', 'CANCELLED');
```

A partial unique index, not an application check. A rule enforced only in application code
is not enforced: two concurrent `POST /api/v1/payment-intents` for one order both pass a
pre-check and both insert. The index is what makes the second lose.

The exclusion set is exactly `FAILED` and `CANCELLED` — the two states from which a
retry is legitimate. `SUCCEEDED`, `PARTIALLY_REFUNDED` and `REFUNDED` still block, because
an order that has been paid must not acquire a second intent.

The pre-check may exist for a friendly message. It may not be trusted. The adapter
translates the constraint violation into `409 ORDER_HAS_ACTIVE_PAYMENT_INTENT`, exactly
as `JpaOrderRepository` does for `uq_orders_merchant_ref`.

Two consequences worth stating so nobody re-derives them wrongly:

- **Split payments are out.** One intent per order plus the amount rule in §2.4 means an
  order cannot be collected in two pieces. That is a deliberate v1 narrowing, and it makes
  overpayment structurally impossible rather than merely CHECK-constrained.
- **`orders.PARTIALLY_PAID` is still reachable later**, via a *partial capture* of a single
  authorized intent (§5), not via a second intent. The two mechanisms are not the same and
  only one is being built.

### 0.3 The state machine, and what is reachable when

SDD §12.1, drawn out:

```
REQUIRES_PAYMENT_METHOD ──attach──▶ REQUIRES_CONFIRMATION ──confirm──▶ PROCESSING
        │                                   │                              │
        │                                   │                   ┌──────────┼──────────┬─────────────┐
        │                                   │                   ▼          ▼          ▼             ▼
        │                                   │              SUCCEEDED    FAILED   REQUIRES_ACTION  AUTHORIZED
        │                                   │                                         │              │
        └───────────cancel──────────────────┴─────────────cancel───────────┐          │        ┌─────┴─────┐
                                                                            ▼      confirm     ▼           ▼
                                                                        CANCELLED ◀──┘     SUCCEEDED   CANCELLED
                                                                                    (back to PROCESSING)

SUCCEEDED ──▶ PARTIALLY_REFUNDED ──▶ REFUNDED        (Refund capability; not this design)
```

Which transitions are live, by PR:

| PR | Newly reachable states | Newly reachable transitions |
|---|---|---|
| 2 | `REQUIRES_PAYMENT_METHOD`, `CANCELLED` | create → `REQUIRES_PAYMENT_METHOD`; `REQUIRES_PAYMENT_METHOD` → `CANCELLED` |
| 3 | `REQUIRES_CONFIRMATION`, `PROCESSING` | attach; confirm; `REQUIRES_CONFIRMATION` → `CANCELLED` |
| 4 | `SUCCEEDED`, `FAILED`, `AUTHORIZED`, `REQUIRES_ACTION` | all four from `PROCESSING`; `REQUIRES_ACTION` → `PROCESSING` |
| 5 | — | `AUTHORIZED` → `SUCCEEDED` (capture); `AUTHORIZED` → `CANCELLED` |
| — | `PARTIALLY_REFUNDED`, `REFUNDED` | **never in this design** — Refund owns them |

All ten states are declared in the enum and in the `ck_payment_intents_status` CHECK from
`V8`, so no PR after the second needs a migration to widen the status column. This is the
Order precedent (`PAID`, `PARTIALLY_PAID`, `EXPIRED` declared and unreachable) applied
deliberately. **No code path may reach a state before the PR that owns it**, and the check
for this is grep, not assertion.

### 0.4 Provider callbacks: duplicated, out of order, and late

Three independent mechanisms. None subsumes another, and the first one is the only one
that is a constraint.

**Duplicated → a safe no-op, by unique constraint.**

```sql
CONSTRAINT pk_provider_callbacks PRIMARY KEY (provider, external_event_id)
```

The handler INSERTs the callback row **inside the same transaction** as the state change.
A duplicate delivery loses on that primary key, the transaction rolls back, and nothing
happened — the intent is untouched, no state-history row, no outbox row. The response is
`200` with `{"outcome": "DUPLICATE"}`.

Note the scope: `(provider, external_event_id)`, **not** merchant-leading. This is the one
table in PayMesh where the merchant-leading rule does not apply, because a provider's
event id is provider-global and the merchant is *derived* from the intent the callback
names, never supplied by the caller. Adding `merchant_id` to that key would let one event
id be processed once per merchant it could be resolved against, which is the exact
duplicate the constraint exists to stop. An implementer "fixing" this to match the house
style would reopen the hole.

Contrast with the idempotency layer, which commits its record *before* the handler runs
(ADR-009). That is correct there and wrong here. There, an orphaned record is recoverable
by the caller with a new key. Here, if the callback row committed separately and the
transition then failed, the provider's event would be permanently swallowed with the
payment left in `PROCESSING` and no way to replay it. One transaction, one commit.

**Out of order → cannot move backwards, by two guards.**

1. *The state machine.* A `PROCESSING` callback arriving after `SUCCEEDED` is not a legal
   transition and is refused. Terminal states absorb.
2. *A monotonic clock on the attempt.* `payment_attempts.last_provider_event_at`. A
   callback whose `occurredAt` is not **strictly after** the stored value is refused. The
   state machine alone is not enough, because the machine contains a cycle:
   `PROCESSING → REQUIRES_ACTION → PROCESSING`. A stale `REQUIRES_ACTION` event arriving
   after the second `PROCESSING` would be a *legal* transition and would drag the payment
   backwards. Ties are refused, not applied: an equal timestamp is either a duplicate
   (caught above) or ambiguous, and refusing is the safe direction.

Both refusals return `200` with an outcome of `IGNORED_STALE` / `IGNORED_TERMINAL`, and
both still write the `provider_callbacks` row so a re-delivery of the stale event is also
deduped. **Never `409`.** A provider retries on any non-2xx; answering a superseded event
with a conflict produces an infinite retry loop against a payment that is already
finished.

Concurrency between two *different* callbacks for one intent is handled by a pessimistic
`SELECT ... FOR UPDATE` on the intent row (SDD §23.3 lists pessimistic locking for exactly
this class). Optimistic `@Version` would make the loser fail and need an application-level
retry; the lock makes it wait, then read the winner's result, then correctly judge itself
stale. This is the one place Payment uses pessimistic locking.

**Late (the provider succeeded, the callback never arrived)** — SDD §21.4. Out of scope:
recovery needs the Provider Simulator's reconciliation file and a job to read it. The
design must not preclude it, which is why the transition logic lives in an application
service the controller calls, not in the controller. A reconciliation job will call the
same service with the same idempotent effect.

### 0.5 What Payment must not do

Stated as sharply as SDD §11.2 and §12 state theirs. Every line here is a review check.

- **Does not edit merchant balances.** Ever, by any path. A `SUCCEEDED` payment is
  operational state; the balance becomes real when the Ledger posts.
- **Does not post ledger entries** and does not know the Ledger's schema exists.
- **Does not write the `orders` table.** Not `status`, not `amount_paid_minor`, not
  anything. Order owns those columns and will move them by consuming `payment.succeeded`.
  That consumer does not exist, so `orders.PAID` and `orders.PARTIALLY_PAID` stay
  unreachable throughout this design. Payment reads Order through the port in §2.5 and
  writes it never. `com.paymesh.order` must not gain an import of `com.paymesh.payment` in
  any PR here.
- **Does not talk to a provider.** In these five PRs there is no outbound call at all. The
  callback endpoint is the seam the Provider Simulator plugs into later.
- **Does not store raw instrument data** and stores provider payloads only after redaction
  (SDD §12.6).
- **Does not decide risk.** There is no risk service; confirm proceeds unconditionally.
  The seam is named in §3.6 and nothing speculative is built for it.
- **Does not let a caller set `status`.** No PATCH, no status field in any request body,
  no setter on the aggregate. Callers request actions.
- **Does not authorize by object id.** A `pi_` in a path grants nothing; the merchant comes
  from the verified token, and another merchant's intent is `404`.
- **Does not mint a credential it cannot verify.** See §2.6 on `clientSecret`.

---

## 1. The transactional outbox

SDD §22.3. A service commits its state change and an `outbox_events` row in the *same*
transaction. A relay later publishes them. Delivery is at-least-once, never exactly-once.

**This PR builds the first half and deliberately stops.** There is no Kafka, no relay and
no publisher. SDD §24 names the resulting state explicitly and calls it safe: *"Payment DB
commits; Kafka unavailable → Outbox remains unpublished → relay retries."* An outbox with
no relay is a permanent instance of a documented, non-corrupting failure mode. A relay
built now would have nothing to publish to, and a publisher interface with one logging
implementation is scaffolding.

What this PR must actually guarantee is the thing that cannot be retrofitted: **the row
and the state change share a transaction.**

### 1.1 The blocker this PR has to solve first

Every application service in this codebase is a `final` class with no Spring annotations,
instantiated from an `@Bean` method (CLAUDE.md, java-coding-conventions §13). That style
**cannot carry `@Transactional`**: Spring's transaction advisor needs a proxy, a class
with no interface needs a CGLIB subclass, and a `final` class cannot be subclassed. The
annotation would be silently inert — the worst possible outcome, because the build stays
green and the two writes commit separately.

There is also, today, no multi-statement transaction anywhere in PayMesh. Every write is a
single `saveAndFlush`, which Spring Data wraps on its own. This PR introduces the first
one.

**Use `TransactionTemplate`, injected explicitly.** It needs no proxy, works with `final`
classes, and being visible in the constructor suits a codebase that wires everything by
hand. Declare the `TransactionTemplate` bean in `SharedConfiguration`.

Do **not** solve this by dropping `final` and annotating. That trades an explicit
three-line change for an invisible, proxy-dependent one, in the one place where "it looks
like it works" is unacceptable.

### 1.2 Migration `V7__create_outbox_events.sql`

```
outbox_events
  event_id        VARCHAR(40)   NOT NULL  -- "evt_" + UUID (ADR-003); the key a consumer dedups on
  merchant_id     VARCHAR(40)   NOT NULL  -- tenant
  aggregate_type  VARCHAR(40)   NOT NULL  -- 'ORDER' | 'PAYMENT_INTENT'
  aggregate_id    VARCHAR(40)   NOT NULL
  event_type      VARCHAR(80)   NOT NULL  -- 'order.created', 'payment.succeeded'
  event_version   INTEGER       NOT NULL  -- SDD 22.1 envelope
  payload         JSONB         NOT NULL
  occurred_at     TIMESTAMPTZ   NOT NULL
  published_at    TIMESTAMPTZ             -- NULL means unpublished; this is the whole status model

  PRIMARY KEY (event_id)
  FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id)
  CHECK (event_version > 0)

INDEX (occurred_at) WHERE published_at IS NULL   -- the relay's claim query, when there is one
```

Notes an implementer will otherwise get wrong:

- **`merchant_id` gets a single-column FK, and that is correct here.** The composite-FK
  rule applies to a foreign key that *carries* a tenant alongside another tenant-scoped id.
  `merchant_id` here **is** the tenant column. There is deliberately no FK on
  `aggregate_id`: it points at a different table depending on `aggregate_type`, and
  polymorphic foreign keys do not exist.
- **No `status` column.** `published_at IS NULL` is the status. ADR-009 already refused a
  status that exists only to be ignored.
- **No `retry_count`, no `last_error`, no dead-letter column.** Those belong to a relay,
  and there is no relay.
- `event_id` is `evt_` + UUID and is public in the envelope (SDD §22.1), so ADR-003 governs
  it — unlike `idempotency_records`, which has no public identifier at all.
- Follow `V3` and `V5`'s commenting standard: every column and constraint explains *why*.

### 1.3 Package `com.paymesh.shared.outbox`

| Type | Layer | Purpose |
|---|---|---|
| `OutboxEvent` | domain | Immutable record; `EventId.generate()`, envelope fields per SDD §22.1 |
| `OutboxWriter` | application | Port. One method: `void append(OutboxEvent event)` |
| `JpaOutboxWriter` + Spring Data repository + entity + mapper | infrastructure | Adapter, ADR-004 |
| `OutboxConfiguration` | infrastructure | Manual wiring |

It lives in `shared` for the same reason the idempotency layer does: it governs every
capability and belongs to none.

`append` performs a plain insert and **assumes a transaction is already open**. It must not
start one, and must not be `REQUIRES_NEW` — that would defeat the entire point. This is
worth a javadoc sentence, because the next reader's instinct is to make it self-contained.

### 1.4 The first producer: `order.created`

`CreateOrderService` wraps its save and its append in one `TransactionTemplate.execute`.
Payload carries `orderId`, `merchantId`, `customerId`, `amountMinor`, `currency`,
`merchantOrderReference`, `status`, `createdAt`.

`order.paid` (SDD §11.5) is **not** emitted: nothing reaches `PAID`.

### 1.5 Testing

Properties, not steps:

- Creating an order writes exactly one `outbox_events` row, with `published_at` null.
- **If the outbox insert fails, no order row exists.** This is the whole PR. Prove it by
  making `append` throw and asserting the `orders` table is empty — a test that only checks
  the happy path passes against two separate transactions and proves nothing.
- The converse: if the order insert fails, no outbox row exists.
- `event_id` is unique across two orders created in the same millisecond.
- `outbox_events.merchant_id` referencing a non-existent merchant is refused by Postgres
  with no application code in the path.

### 1.6 Deliberately deferred

- **The relay, Kafka, and any publisher.** Per SDD §24 an unpublished row is a named safe
  state. Nothing here is wasted when the relay lands: it reads the same table.
- **The inbox (`processed_events`, SDD §22.4).** It is a *consumer's* table, and there are
  no consumers because there is no delivery.
- **An `oldest unpublished outbox age` alert** (SDD §24). It measures a relay.
- **Topic and partition-key mapping** (SDD §22.2). Publishing decides those.

---

## 2. Payment intent core

SDD §12. A payment intent is the *collection* of an order: the order says what is owed,
the intent says how it is being taken. Migration `V8`.

### 2.1 Migration `V8__create_payment_intents.sql`

```
-- Prerequisite for the composite FK below. orders' primary key is (order_id) alone, so
-- Postgres will not accept a reference to (merchant_id, order_id) without this. Redundant
-- with pk_orders, and that redundancy is the price of a tenant-safe FK -- exactly the
-- trade V5 made when it added uq_customers_merchant_customer.
ALTER TABLE orders
    ADD CONSTRAINT uq_orders_merchant_order UNIQUE (merchant_id, order_id);


payment_intents
  payment_intent_id      VARCHAR(40)   NOT NULL  -- "pi_" + UUID (see §2.6 on the prefix)
  merchant_id            VARCHAR(40)   NOT NULL
  order_id               VARCHAR(40)   NOT NULL  -- required in v1; see §2.4
  customer_id            VARCHAR(40)             -- optional, copied from the order or supplied
  amount_minor           BIGINT        NOT NULL
  currency               CHAR(3)       NOT NULL
  capture_method         VARCHAR(16)   NOT NULL  -- AUTOMATIC | MANUAL
  payment_method_type    VARCHAR(20)             -- null until attach; see §3.2
  status                 VARCHAR(32)   NOT NULL
  captured_amount_minor  BIGINT        NOT NULL  -- 0 until capture; PR 5 moves it
  refunded_amount_minor  BIGINT        NOT NULL  -- 0 forever in this design; Refund moves it
  failure_code           VARCHAR(60)
  failure_message        VARCHAR(500)
  cancellation_reason    VARCHAR(200)
  cancelled_at           TIMESTAMPTZ
  description            VARCHAR(500)
  metadata               JSONB
  version                INTEGER       NOT NULL  -- @Version, SDD 23.3
  created_at             TIMESTAMPTZ   NOT NULL
  updated_at             TIMESTAMPTZ   NOT NULL

  PRIMARY KEY (payment_intent_id)
  UNIQUE (merchant_id, payment_intent_id)        -- prerequisite for V9/V10's composite FKs
  FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id)
  FOREIGN KEY (merchant_id, order_id) REFERENCES orders (merchant_id, order_id)
  FOREIGN KEY (merchant_id, customer_id) REFERENCES customers (merchant_id, customer_id)

  CHECK (amount_minor > 0)
  CHECK (currency ~ '^[A-Z]{3}$')
  CHECK (capture_method IN ('AUTOMATIC', 'MANUAL'))
  CHECK (status IN ('REQUIRES_PAYMENT_METHOD', 'REQUIRES_CONFIRMATION', 'PROCESSING',
                    'REQUIRES_ACTION', 'AUTHORIZED', 'SUCCEEDED', 'FAILED', 'CANCELLED',
                    'PARTIALLY_REFUNDED', 'REFUNDED'))
  CHECK (captured_amount_minor >= 0 AND captured_amount_minor <= amount_minor)
  CHECK (refunded_amount_minor >= 0 AND refunded_amount_minor <= captured_amount_minor)
  CHECK ((status = 'CANCELLED' AND cancelled_at IS NOT NULL)
      OR (status <> 'CANCELLED' AND cancelled_at IS NULL))
  CHECK (status = 'REQUIRES_PAYMENT_METHOD' OR status = 'CANCELLED'
      OR payment_method_type IS NOT NULL)      -- past attach, a method is always known
  CHECK (status <> 'SUCCEEDED' OR captured_amount_minor > 0)

UNIQUE INDEX uq_payment_intents_live_per_order
    ON payment_intents (merchant_id, order_id) WHERE status NOT IN ('FAILED', 'CANCELLED')

INDEX (merchant_id, created_at DESC, payment_intent_id DESC)  -- the list endpoint's default page
INDEX (merchant_id, status)
INDEX (merchant_id, order_id)


payment_state_history
  payment_state_history_id BIGINT GENERATED ALWAYS AS IDENTITY
  merchant_id              VARCHAR(40)   NOT NULL
  payment_intent_id        VARCHAR(40)   NOT NULL
  from_status              VARCHAR(32)             -- NULL for creation
  to_status                VARCHAR(32)   NOT NULL
  actor_type               VARCHAR(20)   NOT NULL  -- MERCHANT | PROVIDER | SYSTEM
  actor_id                 VARCHAR(80)
  reason                   VARCHAR(200)
  occurred_at              TIMESTAMPTZ   NOT NULL

  FOREIGN KEY (merchant_id, payment_intent_id)
      REFERENCES payment_intents (merchant_id, payment_intent_id)

INDEX (merchant_id, payment_intent_id, occurred_at)
```

- Every FK that carries a tenant is composite on `(merchant_id, ...)`. A single-column FK
  does not constrain the tenant — this is the exact mistake found in review last session
  and it applies to all three of `orders`, `customers` and `payment_intents` here.
- `payment_state_history` ships in `V8`, not later. Order deferred its history table
  because it had one reachable transition; Payment has two on day one and six by PR 4. A
  history table added at PR 4 would leave every intent created before it with a hole in its
  timeline, and a timeline with a hole cannot be audited.
- Its identity column is a `BIGINT` sequence, not a prefixed id, for the same reason
  `idempotency_records` has no id: ADR-003 governs identifiers that appear in an API, and
  this one never does. Rows are append-only — no UPDATE, no DELETE, ever.
- `refunded_amount_minor` is declared and never moves in this design. It exists so Refund
  needs no migration, same reasoning as Order's `amount_paid_minor`.

### 2.2 Package `com.paymesh.payment`

Standard four layers. Copy Order's file layout.

`domain`: `PaymentIntent` (immutable aggregate, transitions return a new instance),
`PaymentIntentId`, `PaymentIntentStatus`, `CaptureMethod`, `PaymentMethodType`,
`PaymentIntentNotCancellableException` and the other transition refusals. Transition
exceptions thrown by the aggregate live in `domain`, not `application` — otherwise the
dependency direction inverts. (`java-coding-conventions.md` §7 says business-rule failures
live in `application` without acknowledging this; open item 10 already records the
discrepancy. Follow `OrderNotCancellableException`, which is in `domain`.)

`application`: `CreatePaymentIntentService`, `GetPaymentIntentService`,
`ListPaymentIntentsService`, `CancelPaymentIntentService`, `PaymentIntentRepository`,
`PaymentStateHistoryRepository`, `OrderLookup` (§2.5), commands, and the not-found /
not-payable exceptions.

`infrastructure`: `PaymentConfiguration`, the JPA adapters, and
`infrastructure/order/OrderModuleLookup`.

### 2.3 API

All routes merchant-scoped from the token. No route reads a tenant from a path, query or
body.

| Route | Idempotent | Success | Notes |
|---|---|---|---|
| `POST /api/v1/payment-intents` | yes | `201` | → `REQUIRES_PAYMENT_METHOD` |
| `GET /api/v1/payment-intents/{paymentIntentId}` | — | `200` | Another merchant's → `404` |
| `GET /api/v1/payment-intents` | — | `200` | `?limit=&cursor=&status=&orderId=`; same envelope as Orders |
| `POST /api/v1/payment-intents/{paymentIntentId}/cancel` | yes | `200` | Body `{reason?}` |

Register the two writes in `IdempotencyConfiguration.IDEMPOTENT_ROUTES` as path
templates. That registration is the only switch; the layer is otherwise inert.

Create body: `orderId`, `customerId?`, `amountMinor`, `currency`, `captureMethod?`
(default `AUTOMATIC`), `description?`, `metadata?`.

Cursor pagination copies Order's exactly, including the `payment_intent_id` tiebreak.
Ordering by `created_at` alone silently skips rows sharing a boundary instant; Order has a
regression test for this and Payment needs its own.

Errors, through a per-feature `PaymentExceptionHandler`:

| Exception | Status | Code |
|---|---|---|
| `PaymentIntentNotFoundException` | 404 | `PAYMENT_INTENT_NOT_FOUND` |
| `OrderNotPayableException` | 422 | `ORDER_NOT_PAYABLE` |
| `PaymentAmountMismatchException` | 422 | `PAYMENT_AMOUNT_MISMATCH` |
| `OrderHasActivePaymentIntentException` | 409 | `ORDER_HAS_ACTIVE_PAYMENT_INTENT` |
| `PaymentIntentNotCancellableException` | 409 | `PAYMENT_INTENT_NOT_CANCELLABLE` |
| `NoMerchantScopeException` | 403 | `NO_MERCHANT_SCOPE` |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` |
| `IllegalArgumentException` | 400 | `INVALID_REQUEST` |

`ORDER_NOT_PAYABLE` is **one code for three causes** — no such order, not this merchant's
order, order not `PENDING`. Splitting them would turn the endpoint into an oracle for
enumerating another tenant's order ids, which is precisely what ADR-008 stopped the
customer link from becoming.

### 2.4 What create validates

- The order exists, belongs to this merchant, and is `PENDING`. All three failures are
  `ORDER_NOT_PAYABLE`.
- `amountMinor` **equals** the order's amount and `currency` equals the order's currency,
  or `422 PAYMENT_AMOUNT_MISMATCH`. This is the v1 narrowing that makes overpayment
  structurally impossible: one live intent per order, for exactly the order's amount.
- `customerId`, if supplied, matches the order's customer. The composite FK is the actual
  guarantee.
- Amount bounds and metadata caps reuse Order's constants — same rules, so import them or
  restate them identically; do not invent a second ceiling.
- `orderId` is required. An intent with no order has no obligation to collect against, and
  SDD §12.3's example carries one.

Creation is one transaction: insert the intent, insert the `payment_state_history` row
(`NULL → REQUIRES_PAYMENT_METHOD`, actor `MERCHANT`), append `payment.created` to the
outbox. This is why PR 2 depends on PR 1.

### 2.5 Cross-module read: the order link

ADR-008. Payment defines the port **it** needs, in its own package:

```java
// com.paymesh.payment.application
public interface OrderLookup {

    Optional<PayableOrder> find(MerchantId merchantId, String orderId);

    record PayableOrder(
        String orderId,
        String customerId,
        long amountMinor,
        String currency,
        boolean payable
    ) {}
}
```

Deliberately **not** ADR-008's `boolean exists` shape. Payment does not merely need to know
the order is there; it needs the amount and currency to compare against, and whether the
order is in a payable state. The rule is "the consumer states what it needs", not "copy the
previous port's signature".

`OrderModuleLookup` lives in `com.paymesh.payment.infrastructure.order`, delegates to
`GetOrderService`, and is — with `PaymentConfiguration` — the **only** file in
`com.paymesh.payment` that imports `com.paymesh.order`. Extend `ModuleBoundaryTest` to
enforce that, and note while doing so that it currently allowlists by *filename* rather
than path (open item 10), so a second `PaymentConfiguration.java` anywhere would pass.
Fixing that is optional; noticing it is not.

`payable` is `status == PENDING`. Order's status enum does not cross the boundary.

**The reverse import is forbidden.** `com.paymesh.order` gains no knowledge of Payment in
any PR here.

### 2.6 Divergences from the SDD, resolved

Each of these is a place where following the SDD literally would produce a bug or a lie.
They are decided here, not left to an implementer.

| SDD says | This design does | Why |
|---|---|---|
| §12.3: intent id is `pay_01J...` | `pi_` | ADR-003 already lists `pi_` *and* `pay_`, and has spent `pmt_` on payment-method tokens. `pay_` is ambiguous between "payment intent" and a future "payment". `pi_` matches the resource path. Ids are unchangeable once issued; pick the unambiguous one. **Reserve `pay_` and do not use it.** |
| §12.3: `"amount": 99900` | `amountMinor` | The codebase's field name (Order). Conventions doc and code agree that money is minor units; the field name should say so. |
| §12.2: "Merchant API key" | Bearer JWT | PayMesh has no API keys. ADR-007 governs. |
| §12.2: attach uses "checkout client secret" | Merchant bearer auth | There is no checkout auth and no way to verify a client secret. |
| §12.3: response carries `clientSecret` | **Not issued** | A credential nothing can verify is worse than no credential: it looks like an authorization boundary and is not one. Add it with the checkout capability, or not at all. |
| §12.5: `payment_intents.public_id UNIQUE` | The opaque id **is** the primary key | Matches `orders`, `customers`, `merchants`. No surrogate sequential key (ADR-003). |
| §12.5 lists `idempotency_records` under Payment | Already exists, shared, `V4` | Do not recreate it. |
| §11.4 and §12.5 both list `outbox_events` | One shared table, `V7` | Not one per capability. |
| §12.6: "amount and currency are immutable after confirmation" | Immutable from creation | Stricter and simpler; nothing needs to change them earlier. |
| §12.2 path is `/v1/payment-intents` | `/api/v1/payment-intents` | Codebase convention. |
| §12.3: `allowedPaymentMethods` | **Omitted** | Nothing consumes it; no provider exists to constrain. |

Two more, carried over from the existing divergence list in CLAUDE.md and unchanged here:
validation failures return `400` where `rest-api-conventions.md` prescribes `422`, and the
error body is the flat `{code, message, fieldErrors}` shape rather than RFC-7807. Match the
existing code.

### 2.7 Testing

Properties:

- An intent cannot be created against another merchant's order, and the rejection is
  indistinguishable from "no such order".
- An intent cannot be created against a `CANCELLED` order.
- An amount or currency that differs from the order's is refused.
- **A second live intent for one order is refused — and is refused by the database.** Prove
  it by deleting the application pre-check entirely and confirming the integration tests
  stay green, exactly as Order proved `uq_orders_merchant_ref`.
- Two *concurrent* creates for one order produce exactly one intent.
- After the first intent is `CANCELLED`, a second create succeeds.
- Reading another merchant's intent is `404`.
- Cancelling twice: `409` on a fresh idempotency key, a replayed `200` on the same key.
- `POST` without an `Idempotency-Key` is `400`; the same key with a different body is
  `409 IDEMPOTENCY_KEY_REUSED`.
- Every transition writes exactly one `payment_state_history` row, and creation writes one
  with `from_status` null.
- Listing pages correctly across a boundary where two intents share a `created_at`.

---

## 3. Attach payment method, confirm, and attempts

Migration `V9`. Reachable states gain `REQUIRES_CONFIRMATION` and `PROCESSING`.

### 3.1 Migration `V9__create_payment_attempts.sql`

```
payment_attempts
  payment_attempt_id      VARCHAR(40)   NOT NULL  -- "pat_" + UUID (SDD 12.4 uses this prefix)
  merchant_id             VARCHAR(40)   NOT NULL
  payment_intent_id       VARCHAR(40)   NOT NULL
  attempt_number          INTEGER       NOT NULL  -- 1-based, per intent
  provider                VARCHAR(50)   NOT NULL  -- 'SIMULATOR' for now
  provider_reference      VARCHAR(120)            -- the provider's own id; NULL until it answers
  status                  VARCHAR(32)   NOT NULL
  amount_minor            BIGINT        NOT NULL
  currency                CHAR(3)       NOT NULL
  failure_code            VARCHAR(60)
  failure_message         VARCHAR(500)
  last_provider_event_at  TIMESTAMPTZ             -- the out-of-order guard; PR 4 uses it
  request_payload         JSONB                   -- redacted
  response_payload        JSONB                   -- redacted
  version                 INTEGER       NOT NULL
  created_at              TIMESTAMPTZ   NOT NULL
  updated_at              TIMESTAMPTZ   NOT NULL

  PRIMARY KEY (payment_attempt_id)
  UNIQUE (merchant_id, payment_attempt_id)               -- for later composite FKs
  FOREIGN KEY (merchant_id, payment_intent_id)
      REFERENCES payment_intents (merchant_id, payment_intent_id)
  UNIQUE (payment_intent_id, attempt_number)             -- SDD 12.5
  CHECK (attempt_number > 0)
  CHECK (amount_minor > 0)
  CHECK (currency ~ '^[A-Z]{3}$')
  CHECK (status IN ('PROCESSING', 'REQUIRES_ACTION', 'AUTHORIZED', 'SUCCEEDED', 'FAILED'))

UNIQUE INDEX uq_payment_attempts_provider_reference
    ON payment_attempts (provider, provider_reference) WHERE provider_reference IS NOT NULL

INDEX (merchant_id, payment_intent_id, attempt_number DESC)
```

`uq_payment_attempts_provider_reference` is the join key a callback arrives on when it
names a provider reference rather than an intent. It is provider-scoped and not
merchant-leading for the same reason `provider_callbacks` is not — see §0.4.

`attempt_number` is always `1` in this PR, because nothing reaches `REQUIRES_ACTION` yet.
The column and its unique constraint exist so PR 4 needs no migration. Order precedent.

### 3.2 Attach takes a method **type**, not a token

`POST /api/v1/payment-intents/{id}/payment-method`, body `{"paymentMethodType": "CARD"}`.
Allowed: `CARD`, `UPI`, `NET_BANKING`, `WALLET`. Moves
`REQUIRES_PAYMENT_METHOD → REQUIRES_CONFIRMATION`.

SDD §12.2 says "attach tokenized method", meaning a `payment_method_tokens` row. That table
exists (`V3`), has no JPA entity, and **nothing can create a row in it** — the Provider
Simulator, which mints tokens, does not exist. Requiring a token would make the endpoint
uncallable, and building a token-issuing path here would be building the simulator inside
Payment.

Consequences, both good:

- **This PR does not depend on the `V6` FK fix at all.** Payment never reads
  `payment_method_tokens`, so the two work streams are fully independent. If `V6` slips,
  nothing here waits.
- No `PaymentMethodLookup` port, no cross-module read into Customer, no scope creep into a
  capability this PR does not own.

When tokens become real, `payment_method_token_id` joins `payment_intents` as a nullable
column beside `payment_method_type`. Note it and move on.

Idempotent route: yes.

### 3.3 Confirm

`POST /api/v1/payment-intents/{id}/confirm`, idempotent, body `{returnUrl?, device?}`,
response `202 Accepted` (SDD §12.4).

One transaction:

1. Load the intent, tenant-scoped. `REQUIRES_CONFIRMATION` (or `REQUIRES_ACTION`, from PR 4
   onward) or `409 PAYMENT_INTENT_NOT_CONFIRMABLE`.
2. Insert a `payment_attempts` row: next `attempt_number`, provider `SIMULATOR`, status
   `PROCESSING`, `provider_reference` null.
3. Move the intent to `PROCESSING`.
4. Insert `payment_state_history`.
5. Append `payment.processing` to the outbox.
6. Commit.

`202` is the honest code even though nothing asynchronous is invoked: the state is
`PROCESSING` and the outcome is genuinely undecided until a callback arrives. Do not
"simplify" it to `200`.

There is **no outbound call**. `PROCESSING` is where an intent stays until PR 4's callback
endpoint resolves it. That is not a gap in the design; it is what an async payment looks
like with the provider not yet built, and it is why the callback endpoint is the seam the
simulator plugs into.

### 3.4 Cancel widens

`REQUIRES_CONFIRMATION → CANCELLED` becomes legal. `PROCESSING → CANCELLED` does **not**:
an in-flight attempt may already have succeeded at the provider, and cancelling it locally
would produce a payment the platform believes never happened. `409`.

### 3.5 Testing

- Attach from any state other than `REQUIRES_PAYMENT_METHOD` is `409`.
- Attaching twice on the same idempotency key replays; on a fresh key it is `409`.
- Confirm from `REQUIRES_PAYMENT_METHOD` is `409` — a method must be attached first.
- Confirm creates exactly one attempt, numbered `1`.
- **Two concurrent confirms create exactly one attempt**, enforced by
  `UNIQUE (payment_intent_id, attempt_number)`. Prove it at the database level.
- Cancelling a `PROCESSING` intent is `409`.
- The intent and its attempt and its history row and its outbox row all appear, or none of
  them do. Prove it by making the outbox append throw.

### 3.6 Deliberately deferred

- **The synchronous risk decision** (SDD §12.2, §14). Confirm proceeds unconditionally. The
  seam is the single point in `ConfirmPaymentIntentService` between loading the intent and
  creating the attempt; nothing is built for it now.
- **`returnUrl` and `device`** are accepted, validated for shape, and stored on the attempt's
  `request_payload` after redaction. Nothing reads them.
- **Tokenized instruments**, per §3.2.

---

## 4. Provider callbacks

Migration `V10`. Reachable states gain `SUCCEEDED`, `FAILED`, `AUTHORIZED`,
`REQUIRES_ACTION`. This is the hardest PR in the sequence and should be reviewed as such.

### 4.1 Migration `V10__create_provider_callbacks.sql`

```
provider_callbacks
  provider           VARCHAR(50)   NOT NULL
  external_event_id  VARCHAR(120)  NOT NULL  -- the provider's id for this delivery
  merchant_id        VARCHAR(40)   NOT NULL  -- DERIVED from the intent, never supplied
  payment_intent_id  VARCHAR(40)   NOT NULL
  payload_hash       CHAR(64)      NOT NULL  -- SHA-256 of the raw body
  payload            JSONB         NOT NULL  -- redacted
  outcome            VARCHAR(32)   NOT NULL  -- APPLIED | IGNORED_STALE | IGNORED_TERMINAL
  occurred_at        TIMESTAMPTZ   NOT NULL  -- the provider's timestamp
  received_at        TIMESTAMPTZ   NOT NULL
  processed_at       TIMESTAMPTZ   NOT NULL

  PRIMARY KEY (provider, external_event_id)
  FOREIGN KEY (merchant_id, payment_intent_id)
      REFERENCES payment_intents (merchant_id, payment_intent_id)
  CHECK (outcome IN ('APPLIED', 'IGNORED_STALE', 'IGNORED_TERMINAL'))

INDEX (merchant_id, payment_intent_id, received_at DESC)
```

Both `merchant_id` and `payment_intent_id` are `NOT NULL` because a callback naming an
intent this platform does not know is **rejected with `404` and stored nowhere**. There is
nothing to deduplicate for an event that had no effect, and storing rows keyed on a
caller-chosen `external_event_id` for intents that do not exist is unbounded write
amplification on an endpoint reachable with one shared secret. This does mean the endpoint
tells its caller whether an intent exists; the caller is the provider, which necessarily
knows.

### 4.2 Authentication

`POST /internal/v1/provider-callbacks/{provider}`.

This endpoint moves payments to `SUCCEEDED`. It must not be reachable with a merchant's
bearer token — a merchant able to call it could mark their own payment succeeded, which is
a total compromise of the platform's central invariant.

- `SecurityConfiguration` lists `/internal/v1/provider-callbacks/**` as `permitAll()` on
  the Spring chain, so no merchant credential is even considered. Add the line explicitly
  with a comment saying why; the default-deny rule makes silence look like an oversight.
- A dedicated filter in front of the controller verifies an HMAC-SHA256 signature over the
  **raw body** using `paymesh.provider.callback-secret`, in header
  `X-PayMesh-Signature: t=<unix-seconds>,v1=<hex>`. The signed string is `t + "." + body`,
  so a captured signature cannot be replayed onto a different body or a different time.
- Reject if `t` is more than 300 seconds from now, in either direction.
- Compare with `MessageDigest.isEqual` — constant time. A `String.equals` on an HMAC leaks
  the signature one byte at a time.
- Failures return `401` with no detail about which check failed.
- **The secret is fail-closed like the JWT secret.** Extend `DevelopmentSecretGuard` (or
  add a sibling) so the known dev value is refused unless `dev` is the *sole* active
  profile, and the property is `@NotBlank`. Both details from the previous spec's §1 apply
  verbatim, including the trailing-newline and case-folding cases and the warning that
  `ApplicationContextRunner.withPropertyValues` trims its values.

`/internal/**` is deliberately not under `/api/`: it is not the public merchant surface and
must not appear in the Postman collection's merchant folders or in public API docs.

**This route is not registered in `IdempotentRoutes`.** That layer keys on
`merchant + endpoint + Idempotency-Key` from a verified merchant token; a provider has
neither. `provider_callbacks` is its deduplication, and the two must not be confused.

### 4.3 Body

```json
{
  "eventId": "sim_evt_01J...",
  "occurredAt": "2026-08-01T09:35:04Z",
  "paymentIntentId": "pi_01J...",
  "providerReference": "sim_pay_01J...",
  "outcome": "SUCCEEDED",
  "authorizedAmountMinor": 99900,
  "capturedAmountMinor": 99900,
  "failureCode": null,
  "failureMessage": null,
  "actionUrl": null
}
```

`outcome` is one of `AUTHORIZED`, `SUCCEEDED`, `FAILED`, `REQUIRES_ACTION`. Amounts are
integer minor units and are checked against the intent — a callback claiming a different
amount than the intent authorizes is `IGNORED_TERMINAL` with the mismatch recorded, never
applied. A provider does not get to change what is owed.

### 4.4 Processing

Signature verification happens first, outside any transaction, before the body is parsed
as JSON.

Then, in **one** transaction:

1. Resolve the intent by `paymentIntentId` (falling back to
   `(provider, providerReference)` on the attempt). Not found → rollback, `404`, nothing
   stored.
2. `SELECT ... FOR UPDATE` the intent row. Pessimistic, per §0.4.
3. `INSERT INTO provider_callbacks`. **Unique violation → roll back the whole
   transaction and return `200 {"outcome": "DUPLICATE"}`.** Nothing else has happened.
4. **Staleness:** `occurredAt <= attempt.last_provider_event_at` → outcome
   `IGNORED_STALE`; keep the callback row, apply nothing, commit, `200`.
5. **Legality:** the requested transition is not legal from the intent's current status →
   outcome `IGNORED_TERMINAL`; keep the callback row, apply nothing, commit, `200`.
6. **Apply:** update the intent and the attempt, set
   `attempt.last_provider_event_at = occurredAt`, insert `payment_state_history`
   (actor `PROVIDER`), append the outbox event, outcome `APPLIED`.
7. Commit. `200`.

Step 6's outbox append and the intent update sharing one commit **is** SDD §12.6's
invariant. It is the reason §1 precedes this.

Outbox events emitted: `payment.succeeded`, `payment.failed`, `payment.authorized`,
`payment.requires_action`. Payload carries `paymentIntentId`, `merchantId`, `orderId`,
`amountMinor`, `capturedAmountMinor`, `currency`, `status`, `occurredAt`.

**Every path except "unknown intent" returns `200`.** A provider retries on non-2xx.
Answering a duplicate or a superseded event with `409` produces an infinite retry against a
payment that is already finished — a self-inflicted outage that looks like a provider
problem.

### 4.5 Redaction

`payload` and the attempt's `request_payload` / `response_payload` are stored **after**
redaction (SDD §12.6). Redact by allowlist, not denylist: keep the fields this design names
and drop everything else. A denylist grows a hole the first time a provider adds a field.

### 4.6 Testing

Properties, each of which should fail if the corresponding mechanism is removed:

- The same callback delivered twice applies once. Assert on `payment_state_history` row
  count, not on the response — the response is `200` either way, which is the point.
- Two identical callbacks delivered **concurrently** apply once. Prove it at the database
  level; a test that passes with the unique constraint dropped proves nothing.
- A `PROCESSING` callback arriving after `SUCCEEDED` leaves the intent `SUCCEEDED` and
  returns `200`.
- **A stale `REQUIRES_ACTION` arriving after a later `PROCESSING` does not move the payment
  backwards.** This is the case the state machine alone does not catch; write it explicitly
  and verify it by removing the `last_provider_event_at` comparison.
- Two *different* callbacks for one intent, concurrently, produce a coherent final state
  and exactly two `provider_callbacks` rows.
- A callback with no signature, a wrong signature, a stale timestamp, or a signature over a
  different body is `401` and changes nothing.
- A callback naming an unknown intent is `404` and writes no row.
- A `SUCCEEDED` callback writes exactly one `outbox_events` row, in the same transaction —
  prove it by making the append throw and asserting the intent is still `PROCESSING`.
- A callback claiming an amount the intent does not authorize is not applied.

### 4.7 Deliberately deferred

- **The Provider Simulator** (SDD §13). This PR builds the endpoint it will call. Tests and
  Postman drive it directly, which is also how a reconciliation replay will.
- **Lost-callback recovery** (SDD §21.4). Needs the simulator's reconciliation file and a
  job. The transition logic is in a service, not the controller, so that job can reuse it.
- **Per-provider signing secrets and rotation.** One secret, one provider.
- **Callback retry/ordering telemetry.**

---

## 5. Manual capture

No migration. `captured_amount_minor` and `capture_method` already exist from `V8`.

| Route | Idempotent | Success | Transition |
|---|---|---|---|
| `POST /api/v1/payment-intents/{id}/capture` | yes | `200` | `AUTHORIZED → SUCCEEDED` |
| `POST /api/v1/payment-intents/{id}/cancel` (widened) | yes | `200` | `AUTHORIZED → CANCELLED` |

Capture body: `{"amountMinor": ?}`, defaulting to the full authorized amount. A partial
capture sets `captured_amount_minor < amount_minor` and the intent still reaches
`SUCCEEDED` — that is what will later make `orders.PARTIALLY_PAID` reachable, through the
`payment.succeeded` consumer that does not exist yet. Capturing more than authorized is
`422`; the CHECK is the guarantee.

Capture is only legal when `capture_method = 'MANUAL'`; an `AUTOMATIC` intent is captured by
the provider callback that reports `SUCCEEDED`.

**A known future change, stated so nobody treats this as settled.** Capture here moves
`AUTHORIZED → SUCCEEDED` synchronously and emits `payment.succeeded`. With a real provider
it becomes `AUTHORIZED → PROCESSING` plus a capture callback. The synchronous path is
correct for a platform with no provider and will be replaced, not extended.

This is the one PR in the sequence that could be dropped or postponed without weakening
anything else — if the Provider Simulator is pulled forward, build it after.

Testing: capture from any non-`AUTHORIZED` state is `409`; capture on an `AUTOMATIC` intent
is `409`; over-capture is `422`; partial capture leaves `captured_amount_minor` below
`amount_minor` and the status `SUCCEEDED`; capture twice is a replay on the same key and a
`409` on a fresh one.

---

## 6. Sequencing, parallelism and review

| PR | Migration | Depends on | May start when |
|---|---|---|---|
| 1 outbox | `V7` | — | now |
| 2 intent core | `V8` | 1 (uses `OutboxWriter`) | now, branch stacked on 1 |
| 3 confirm + attempts | `V9` | 2 | 2 merged |
| 4 provider callbacks | `V10` | 3 | 3 merged |
| 5 manual capture | — | 4 | 4 merged |

- **1 and 2 may be authored in parallel** by stacking 2's branch on 1's. They touch
  disjoint packages and disjoint migrations; the only shared file is
  `IdempotencyConfiguration` (2 adds routes, 1 does not). Stacking costs mechanical
  conflicts at merge, which this project has absorbed before.
- **3, 4 and 5 are strictly serial.** Each one's reachable states depend on the previous
  one's, and 4 in particular cannot be reviewed without 3's attempts table.
- **The `V6` `payment_method_tokens` fix is independent of all five** — see §3.2. Nothing
  here reads that table.
- Migration numbers are assigned in this document precisely so two agents cannot collide.
  `V6` is not to be touched by anyone working from this spec.

Each PR carries, in the same PR:

- `docs/api/postman/paymesh.postman_collection.json` — a folder that runs top to bottom,
  with assertions that assert. PR 4's folder needs a pre-request script that computes the
  HMAC; write it, do not paste a fixed signature.
- `docs/decisions/` — **ADR-010** transactional outbox in PostgreSQL with no relay (PR 1;
  include the `final`-class / `TransactionTemplate` finding, it is the non-obvious part);
  **ADR-011** one live payment intent per order, enforced by a partial unique index (PR 2);
  **ADR-012** provider callback deduplication and ordering (PR 4). Numbers assigned here so
  two implementers do not both write ADR-010.
- `docs/project-status.md` — at the end of the session, not during.

Nothing merges on the author's report. An independent reviewer re-runs the suite and, where
a test protects an invariant, breaks the implementation to confirm the test catches it.
Every sabotage named in §1.5, §2.7, §3.5 and §4.6 is a specific instruction to that
reviewer.

---

## 7. Open questions this spec does not settle

Surfaced rather than silently decided, because unsurfaced ambiguity has already caused
three bugs in this project.

1. **`orders.status` never moves.** Payment will not write it (§0.5) and the
   `payment.succeeded` consumer does not exist, so an order stays `PENDING` after its
   payment succeeds. This is correct-by-design and *visibly wrong* to anyone reading the
   API. It resolves when the outbox gets a relay and Order gets a consumer. Until then it
   belongs in `project-status.md` as a known inconsistency, not as a bug report.
2. **Nothing expires an order or an intent.** Open item 9 already records this for orders.
   An intent left `REQUIRES_PAYMENT_METHOD` forever also holds the live-intent slot for its
   order forever, so the sweeper matters more after PR 2 than before it. It is still not
   built here.
3. **`payment_state_history` has no reader.** No endpoint exposes a timeline. The table is
   written from PR 2 because a history with a hole is worthless; exposing it is a later,
   separate decision.
4. **A stranded `IN_PROGRESS` idempotency record still wedges a key** (open item 6). Payment
   inherits it. With four idempotent Payment routes the surface grows, and the merchant's
   escape — a fresh key — now runs into `uq_payment_intents_live_per_order` rather than
   succeeding. That is safe but the error message will be confusing. Worth a reaper sooner
   than "eventually".
5. **`java-coding-conventions.md` §7 still says business-rule failures live in
   `application`**, which cannot be true for exceptions thrown by an aggregate. Payment adds
   five more such exceptions in `domain`. Fix the doc or acknowledge the exception in it.
