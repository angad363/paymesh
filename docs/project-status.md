# PayMesh — Project Status and Roadmap

_Last updated: 2 August 2026. Update this file at the end of a working session, not
during one._

This is the pick-up-here document. It records what exists, what has actually been
verified, what is deliberately unfinished, and what comes next. For *why* a design
looks the way it does, read the ADRs in `docs/decisions/`; for the target
architecture, read the SDD.

---

## Where the project is

**All eight of Phase 1's capabilities are built**: **Merchant**, **Identity & Access**,
**Customer**, **Order**, **Payment**, the **Provider Simulator**, the **Ledger**, and
**Refund**. Underneath them sit four
pieces of platform work that had to land first: the application refuses to boot on the committed
JWT signing key, public writes are idempotent through PostgreSQL, a transactional outbox lets a
state change and the event announcing it commit together, and **that outbox is finally read**.
A scheduled relay, an in-process dispatcher and a `processed_events` inbox deliver events to
consumers, and Order is the first consumer (ADR-016).

**1118 tests, 0 failures.** Twenty Flyway migrations (V1–V20). Twenty-four ADRs. The Postman
collection runs fourteen folders, the newest showing money go back out.

**The Ledger is the financial source of truth, and as of this session it exists** (ADR-018).
A captured payment posts a balanced double-entry journal, and `GET /api/v1/balances` reports
what PayMesh owes a merchant. What makes it trustworthy is not the Java: debits-equal-credits
is a DEFERRED constraint trigger checked at COMMIT, immutability is a trigger, single-currency
is a composite foreign key. The integration tests insert lopsided journals with raw SQL and the
database refuses them with the application entirely out of the path.

The Provider Simulator is the first module written to be **removed from a deployment**. Every other
capability is built to be extracted eventually; this one holds no reference to PayMesh at all — no
shared type, no shared table, no import in either direction — so its only influence is an HTTP POST
of a signed body at the callback route, exactly as a third party's would be.

The application is still a single deployable with strict module boundaries — the
modular-monolith-first plan from ADR-001 and SDD §30.1. Nothing has been extracted
into a service, and nothing should be until the API and event contracts are proven.

Payment is the first capability where the platform work paid off rather than being built
alongside: creating an intent writes the intent, its first state-history row and its
event in one transaction, on an idempotency layer and an outbox that already existed.

---

## How the pieces fit

One authenticated write, end to end. Worth reading once before touching any capability,
because every layer below answers a different question and conflating two of them is how
this shape usually fails.

| # | Step | What it decides | Tenant check here? |
|---|---|---|---|
| 1 | **Spring Security filter chain** (`SecurityConfiguration`) | *Who is calling.* `BearerTokenAuthenticationFilter` verifies the HS256 access token and populates the `SecurityContext`. No token, bad signature, expired → `401`. | **No.** The edge cannot see which row is being touched, so it cannot answer tenancy (ADR-007). |
| 2 | **`IdempotencyFilter`** | *Has this exact request already run.* Ordered deliberately **after** security, because the record's key is `merchant + endpoint template + Idempotency-Key` and the merchant does not exist until the token is verified. Missing header on a registered route → `400`; same key + different body hash → `409`; `COMPLETED` → the stored response is replayed with `Idempotency-Replayed: true`. | Indirectly — the key is *scoped by* merchant, so two tenants cannot collide on one key. |
| 3 | **Controller** (`OrderController`, `PaymentIntentController`, …) | *Which tenant is this.* The `AuthenticatedCaller` argument resolver hands the handler the caller's roles, and `caller.requireSingleMerchant()` reduces them to exactly one `MerchantId`. | **Yes — this is where the tenant is *derived*.** It is never read from a request body; no write request record has a `merchantId` field. |
| 4 | **Application service** | *Is this allowed, and what changes.* Takes the `MerchantId` as an argument and passes it into every repository call. A cross-module read goes through the consumer's own port (`CustomerLookup`, `OrderLookup`), which is merchant-scoped too — so another tenant's row is simply *not found*, and the caller gets the same answer as for a row that never existed. | **Yes — every query is scoped, including through the ports.** |
| 5 | **Transaction boundary** (`TransactionTemplate`, inside the service) | *What commits together.* The aggregate row, its state-history row where one exists, and the `outbox_events` row. All or nothing — an event can never survive a rolled-back state change, and a committed state change can never lose its event (ADR-010). | Inherited from step 4; nothing inside re-derives the tenant. |
| 6 | **PostgreSQL** | *The last word.* Composite tenant foreign keys, unique constraints and the partial unique index. | **Yes, and this is the only one that cannot be bypassed.** Everything above it is a pre-check that exists to turn a violation into a readable `409`/`422` instead of a `500`. |
| 7 | **Response, back through the filter** | The status and body are stored against the idempotency record, which is marked `COMPLETED`. If the handler threw, or the status is 5xx, the record is **deleted** so a retry is a real retry. | — |

The load-bearing property of that list: **steps 3–5 are advisory and step 6 is not.** The
integration tests prove this by bypassing the application pre-checks entirely and staying
green.

---

## What of the SDD is implemented

The SDD describes ~15 services across 31 sections. This is what the code actually covers.

| Capability / piece | SDD sections | State |
|---|---|---|
| Identity & Access | §8 (all), §8.6 token lifetimes | Built. No API keys, no OAuth2/OIDC, no denylist. |
| Merchant | §9 (all) | Built. Registration only; no team roles, API keys or webhook setup (§9.3). |
| Customer | §10, and §10.6's hash/display split | Built in the encrypted *shape*; encryption itself deferred (ADR-006). No payment-method endpoints. |
| Order | §11.1–§11.4, §11.6 | Built, including `order_state_history`. §11.5's events are written **and now published**. Every status in the enum is reachable. |
| Payment | §12.1 (partially), §12.2–§12.3, §12.5–§12.6 | Core built. **§12.4 confirm is not implemented**; 2 of the 10 §12.1 states are reachable. |
| Idempotency & concurrency | §23.1–§23.2 | Built in PostgreSQL. §23.3's Redis accelerator: not built, deliberately. |
| Events / outbox | §22.1 envelope, §22.3 outbox, §22.4 inbox, §24 durability | Built: in-transaction write, a scheduled relay, an in-process dispatcher, `processed_events`, and one consumer (ADR-016). **§22.2 (Kafka topics and partition keys) does not exist, deliberately** — the consumer contract is a broker's, so the transport can be swapped without touching a consumer. §24's alerting does not exist either. |
| API Gateway / Edge | §7 | Partial. API keys exist (ADR-022) and HMAC guards both callback routes; rate limiting is absent. |
| Provider Simulator | §13.1–§13.2, §13.5–§13.6 | Built. **§13.3's payouts and §13.4's `provider_payouts` are not** — Settlement is Phase 2 and has no consumer. Percentage-based injection is deliberately absent (ADR-017 §5). |
| Risk & Fraud | §14 | Not started. |
| Ledger | §15.1–§15.2, §15.6 | Core built (ADR-018): double-entry accounts, journals, immutable entries, and a merchant balance. **§15.3's internal posting API is deliberately absent** — the only writer is an event consumer, so every posting traces to a committed state change. **§15.5's `balance_holds` and `account_balances` are not built**: nothing reserves funds until Settlement, and a SUM over entries cannot drift the way a projection can. No fee split (§15.2) — there is no fee schedule. No reversal path yet; Refund brings it. |
| Refund | §16.1–§16.3, §16.5–§16.6 | Built (ADR-019). Create, read, list, cancel, and a Refund-owned callback route. **§16.4's `refund_reservations` and `refund_attempts` are not built** — the first is a second copy of what `refunds.status` says, the second is for a conversation a refund does not have. §16.3's ops retry route is absent. §16.6's third line — reconciling a lost callback — is the known gap. |
| Settlement, Webhook, Notification/Reporting/Audit, AI Ops | §17–§20 | Not started. |
| End-to-end workflows | §21 | Only the create-order → create-intent prefix exists; §21.4 reconciliation is absent. |
| Security & privacy | §25 | Partial: authn, tenant isolation, secret guards. No encryption at rest, no key management, no audit trail beyond `security_events`. |
| Observability | §26 | `/actuator/health` and `/actuator/info`. No OpenTelemetry, metrics, or structured correlation ids. |
| Deployment / IaC | §27 | Not started. `infrastructure/` and `scripts/` are empty. |

---

## What is built

### Merchant — `com.paymesh.merchant`

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/v1/merchants` | public | Self-service onboarding; precedes having an account |
| `GET /api/v1/merchants/{id}` | bearer token | Caller must hold a role at that merchant, else 404 |

Domain normalizes on the way in (trims the business name, lowercases the email,
uppercases country and currency) and enforces format invariants. `uq_merchants_email`
is the real uniqueness guard; the adapter translates its violation into a 409 so the
loser of a registration race is not handed a 500.

### Identity & Access — `com.paymesh.identity`

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/v1/auth/register` | public | Optional `merchantId` grants `MERCHANT_ADMIN` scoped to it |
| `POST /api/v1/auth/login` | public | Returns a 15-minute HS256 JWT + a 30-day opaque refresh token |
| `POST /api/v1/auth/token/refresh` | refresh token | Rotates; reuse revokes the whole family |
| `POST /api/v1/auth/logout` | refresh token | Idempotent, revokes the family |

Properties that are load-bearing and easy to break by accident:

- **Login is not an oracle.** Unknown email and wrong password return byte-identical
  bodies; an unknown email still runs one BCrypt verification against a fixed hash so
  timing does not differ; account status is checked *only after* the password
  verifies, which is why `USER_NOT_ACTIVE` is 403 rather than 401.
- **Rotation is a compare-and-swap**, not read-then-write. `and revoked_at is null`
  lives in the UPDATE, so the database decides which of several concurrent callers
  actually spent a token. Losing that race is indistinguishable from replay and is
  treated identically. Regression test verified by breaking the fix.
- **Refresh tokens are opaque 256-bit random values, SHA-256 hashed at rest** — not
  BCrypt (refresh must *find* the row by hash) and not JWTs (an opaque token is
  revocable).
- **The application will not start on the committed dev signing key.** Secrets live in
  `application-dev.yaml`, activated only when `dev` is the *sole* active profile;
  everything else must supply `PAYMESH_SECURITY_JWT_SECRET`. See below.

### Customer — `com.paymesh.customer`

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/v1/customers` | bearer token | Tenant comes from the token; the request record has no `merchantId` field |
| `GET /api/v1/customers/{id}` | bearer token | Another merchant's customer returns 404 |

`merchant_reference` is unique **per merchant**, not globally. Every index is
composite and merchant-leading. PII is stored in the encrypted *shape* — display
columns that are never queried, separate hash columns carrying the indexes — but is
**plaintext today** (ADR-006).

### Order — `com.paymesh.order`

| Endpoint | Auth | Idempotent | Notes |
|---|---|---|---|
| `POST /api/v1/orders` | bearer token | yes | `ord_` id; optional customer link |
| `GET /api/v1/orders/{id}` | bearer token | — | Another merchant's order returns 404 |
| `GET /api/v1/orders` | bearer token | — | Cursor pagination, optional status filter |
| `POST /api/v1/orders/{id}/cancel` | bearer token | yes | Only from `PENDING`, else 409 |

State machine: **every status is reachable.** `PENDING → CANCELLED` is a merchant request,
`PENDING → EXPIRED` is the sweeper (ADR-014), and `PENDING → PAID` / `PENDING → PARTIALLY_PAID`
is Order's own consumer of `payment.succeeded` (ADR-016) — the last two were declared in V5 and
unreachable until this session. `PARTIALLY_PAID` does not lead to `PAID`: a second collection
against one order is structurally impossible while an order holds at most one live intent for
exactly its own amount.

Properties worth not breaking:

- **The customer FK is composite on `(merchant_id, customer_id)`**, not on
  `customer_id` alone. A single-column FK would have permitted an order to name *any*
  customer on the platform, leaving only an advisory application check between a
  merchant and another tenant's data. This required adding
  `uq_customers_merchant_customer` to `customers`. Proven at the database level: a raw
  JDBC insert of a cross-tenant order is refused by Postgres with no application code
  in the path. `customer_id` stays nullable and Postgres FKs default to `MATCH
  SIMPLE`, so guest orders with no customer still work.
- **Cursor pagination breaks ties on `order_id`.** Ordering by `created_at` alone
  silently *skips* rows that share a boundary instant — three orders, `limit=2`, and
  the third vanishes while every page still looks well-formed. Regression test
  verified by removing the tiebreak.
- **Two independent dedup rules, deliberately.** `Idempotency-Key` replays a response;
  `uq_orders_merchant_ref` catches a genuine double-submit that arrives with a
  *fresh* key. Neither subsumes the other. Verified by deleting the application's
  pre-check entirely and confirming every integration test stayed green — the
  constraint is the guard, the pre-check only buys a friendlier message.
- **Order reads Customer through a port it owns** (`CustomerLookup`), implemented in
  `order.infrastructure`. Nothing in Order's `api`, `application` or `domain` sees
  Customer. ADR-008.
- **Order learns that a payment succeeded from an EVENT, not from a call**, and that is what
  keeps `ModuleBoundaryTest.orderNeverImportsPayment`'s allowlist empty while Order writes
  `orders.status` on Payment's news. The consumer reads the payload as a `Map`, imports nothing
  from `com.paymesh.payment`, and takes the merchant from the envelope rather than the payload.
  **The PAID / PARTIALLY_PAID split compares the captured figure against the ORDER's amount, never
  the payment's** — the two agree today, so reading the payment's would pass every test that did
  not check, and would mark an order fully paid on the strength of a document that is not the
  obligation.

### Payment — `com.paymesh.payment`

| Endpoint | Auth | Idempotent | Notes |
|---|---|---|---|
| `POST /api/v1/payment-intents` | bearer token | yes | `pi_` id; → `REQUIRES_PAYMENT_METHOD` |
| `GET /api/v1/payment-intents/{id}` | bearer token | — | Another merchant's intent returns 404 |
| `GET /api/v1/payment-intents` | bearer token | — | Cursor pagination, optional `status` and `orderId` filters |
| `POST /api/v1/payment-intents/{id}/cancel` | bearer token | yes | Only from `REQUIRES_PAYMENT_METHOD`, else 409 |

All ten statuses are declared in the enum and the CHECK constraint; **only
`REQUIRES_PAYMENT_METHOD` and `CANCELLED` are reachable** — verified by grep, not by
assertion. Attach, confirm, provider callbacks and capture are the remaining PRs.

Properties worth not breaking:

- **An order holds at most one live payment intent, and the database enforces it**
  (ADR-011). `uq_payment_intents_live_per_order` is a partial unique index excluding
  exactly `FAILED` and `CANCELLED`. The application pre-check exists only for a friendlier
  message: the integration tests bypass it entirely and still pass, because the index is
  the guard. Two concurrent creates for one order produce exactly one intent — verified by
  downgrading the index to non-unique, which yields `expected: 1L but was: 2L`.
- **The slot is only defensible because every state a customer can strand an intent in has
  a route to `CANCELLED`.** A slot that cannot be released kills the order, which is worse
  than the overpayment the index prevents. `PROCESSING` is the deliberate exception and the
  cost is written down in ADR-011, not discovered later.
- **Creation is one transaction across three writes** — the intent, its `NULL →
  REQUIRES_PAYMENT_METHOD` history row, and `payment.created`. Cancellation is one
  transaction across three more, including `payment.cancelled`. Verified by removing the
  `TransactionTemplate` wrap, which leaves an intent behind with no event and no timeline.
- **Payment never writes the `orders` table**, posts no ledger entry, talks to no provider,
  and mints no credential it cannot verify (design spec §0.5, re-checked line by line in
  review). It reads Order through an `OrderLookup` port it owns, and
  `ModuleBoundaryTest` now asserts both directions — including that Order **never** imports
  Payment.
- **`ORDER_NOT_PAYABLE` is one code for three causes** — no such order, another merchant's
  order, not `PENDING`. Splitting them would make the endpoint an oracle for enumerating
  another tenant's order ids. The API test compares the three responses byte for byte.
- **The exact-amount rule makes overpayment structurally impossible**, not merely
  CHECK-constrained: one live intent per order, for exactly the order's amount. Split
  payments are out as a direct consequence.
- `PaymentIntent` restates Order's `MAX_AMOUNT_MINOR` and metadata caps rather than
  importing them, so Payment's domain does not depend on Order's. Review confirmed the
  values are identical today; if they drift, an order could exist that no intent may
  collect.

### Ledger — `com.paymesh.ledger`

**The financial source of truth**, and the module SDD §30.1 schedules for extraction last.

| Endpoint | Auth | Notes |
|---|---|---|
| `GET /api/v1/balances` | Bearer | One row per currency the merchant has been paid in; empty list, never 404 |

There is **no write endpoint**, deliberately (ADR-018 §3). The only writer is a consumer of
`payment.succeeded`, which means **every posting traces back to a committed payment**. SDD §15.3's
`POST /internal/v1/ledger/transactions` would be a second way into the financial source of truth
with no originating event to reconcile against.

Properties worth not breaking:

- **The invariants are in PostgreSQL, not in Java.** Debits-equal-credits is a DEFERRED constraint
  trigger checked at COMMIT; immutability is a trigger refusing UPDATE and DELETE; single-currency
  is a pair of composite foreign keys; tenant consistency is a check inside the balance trigger.
  The integration tests insert lopsided journals with raw SQL and the database refuses them.
  Verified by dropping the balance trigger, which turns exactly two raw-SQL tests red and leaves
  every Java-level test green.
- **A correction is a new reversal journal, never an edit**, and the immutability trigger is what
  makes that the only available option rather than the disciplined one. Refund exercises it.
- **The balance is a SUM over entries, not a projection.** SDD §15.5's `account_balances` is not
  built: a second copy of a number the entries already determine can drift from them silently, and
  the repair is the query being avoided. Carries a `ponytail:` marker naming the upgrade path.
- **Accounts are opened on first use**, through an `INSERT … ON CONFLICT DO NOTHING` followed by a
  read. Not a catch around a failed insert: this runs inside the dispatcher's transaction, and in
  PostgreSQL *any* error aborts the enclosing transaction, so the recovery read would be the first
  casualty.
- **Two account types out of SDD §15.1's nine.** Each of the others needs a producer that does not
  exist — a settlement schedule, holds, a fee schedule. An account that reads zero forever implies
  a capability that is missing.
- **No platform fee.** There is no fee schedule anywhere in this codebase, and a made-up rate would
  sit in rows nothing can ever edit.

### Refund — `com.paymesh.refund`

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/v1/refunds` | Bearer + Idempotency-Key | `ref_` id; omit `amountMinor` to refund what is **left** |
| `GET /api/v1/refunds/{id}` | Bearer | `404` for another merchant's, never `403` |
| `GET /api/v1/refunds` | Bearer | Keyset pagination, newest first |
| `POST /api/v1/refunds/{id}/cancel` | Bearer + Idempotency-Key | `409` almost always — see below |
| `POST /internal/v1/refund-callbacks/{provider}` | HMAC signature | Refund's own route (ADR-019) |

`PENDING → PROCESSING → SUCCEEDED | FAILED`, or `PENDING → CANCELLED`. Create writes PENDING and
submits in one transaction, so the merchant never observes PENDING through the API.

Properties worth not breaking:

- **The over-refund guard is a LOCK plus a trigger, and the lock is the mechanism.** A row lock on
  the payment intent, taken inside the create transaction before head-room is read, serializes
  concurrent refunds of one payment. The deferred trigger is the backstop for raw SQL and
  migrations. <b>The trigger alone does not work</b>: a constraint trigger's query runs on the
  snapshot of the statement that queued it, so two simultaneous refunds each see a world without
  the other and both commit. That was measured, not reasoned — `RefundConcurrencyTest` let both
  through before the lock existed. ADR-019 §4.1.
- **Everything except FAILED and CANCELLED counts against the captured amount.** A refund in flight
  has moved no money yet but the provider may be about to; counting only SUCCEEDED would let a
  merchant queue ten full refunds while the first is with the provider, each individually valid.
- **The comparison is against `captured_amount_minor`, never `amount_minor`.** On a partial capture
  the two differ by money that was never collected.
- **A second trigger pins the currency to the payment's.** 5000 JPY against a 5000 INR capture
  passes the amount check *exactly* — integers carry no currency. Unreachable through the API,
  because the request record has no currency field at all.
- **Refund's callback route is its own**, with its own secret property and its own dedup table.
  Sharing Payment's would have meant Payment knowing refunds exist in order to route the callback.
  The HMAC filter itself moved to `shared` so there is one implementation of that check rather than
  two copies.
- **Cancel answers 409 almost always**, and that is honest rather than broken: PROCESSING means the
  provider may already have moved the money, so reporting CANCELLED would be PayMesh's opinion
  contradicting a bank statement. Its real use is clearing a refund that failed to submit.
- **Refund is a leaf.** It imports Payment through exactly one adapter plus its configuration
  (ADR-008); nothing imports Refund. Payment learns that a refund succeeded from an event.

### Provider Simulator — `com.paymesh.simulator`

**Not the merchant API.** Everything is under `/sim/v1/**`, authenticated by a dedicated shared key
in `X-PayMesh-Simulator-Key` and nothing else — a merchant bearer token is refused (ADR-017 §3).

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /sim/v1/payments` | simulator key | `sim_pay_` id; `201` on create, **`200` on a replay** |
| `POST /sim/v1/payments/{id}/capture` | simulator key | Only from `AUTHORIZED`, else 409 |
| `POST /sim/v1/refunds` | simulator key | `sim_ref_` id; **enqueues no callback — now a gap, not a decision** |
| `GET /sim/v1/reconciliation/{date}` | simulator key | One UTC day of the provider's own truth |
| `POST /sim/v1/failure-profile` | simulator key | Last-write-wins; not idempotency-keyed |

Deterministic tokens, and **the token wins where it names a behaviour; the profile fills in where it
does not**: `tok_sim_success`, `tok_sim_decline`, `tok_sim_3ds`, `tok_sim_timeout` (**no callback row
at all**), `tok_sim_duplicate`, `tok_sim_stale`.

Properties worth not breaking:

- **It holds no reference to PayMesh in either direction, and `ModuleBoundaryTest` asserts it with an
  empty allowlist on both.** That is stricter than every other pair in that file, all of which permit
  an adapter. `CallbackBody` restates `ProviderCallbackRequest` and `SimulatedOutcome` restates
  `ProviderOutcome` rather than importing them: the contract is *published*, not *shared*. The cost
  is that the two can drift, and `SimulatorCallbackDeliveryIntegrationTest` is what goes red when
  they do — the notification a shared type would have suppressed.
- **The dispatcher is the design; an inline POST would make the module worthless.** Every failure
  mode worth simulating is a property of *when and how often* a callback arrives, and none of them is
  expressible from inside the create handler. Delayed, lost, duplicated, out-of-order and retried all
  fall out of `deliver_after`, `external_event_id` and `occurred_at` on `provider_outbound_callbacks`.
- **The body is serialized once at enqueue time and stored as `TEXT`, not `JSONB`.** A `JSONB` round
  trip normalises key order and whitespace, so the bytes read back would not be the bytes signed. The
  dispatcher signs the stored string and posts that same string. Verified by re-serializing after
  signing, which turns 6 of the 7 delivery tests red.
- **The signature timestamp is taken at delivery, not enqueue.** A callback deliberately delayed ten
  minutes must not arrive carrying a ten-minute-old `t` and be refused as stale. `occurred_at` and
  `t` are different facts produced at different moments; conflating them makes every delayed-callback
  case fail with a 401.
- **A third guarded secret.** `paymesh.simulator.api-key` is in `DevelopmentSecretGuard.GUARDED`
  alongside the JWT and callback secrets, and it is not the lesser one: `POST /sim/v1/payments`
  queues the callback that marks a payment `SUCCEEDED`, which is the same power as forging a
  callback, reached one step earlier and without signing anything.
- **The timer is absent under `dev`, not merely idle.** `@ConditionalOnProperty` removes the bean.
  `dev` is the profile every `@SpringBootTest` runs under, and a dispatcher POSTing at PayMesh
  mid-test would mutate the rows under assertion. Verified by flipping the flag, which turns
  `SimulatorConfigurationTest` red.
- **`ck_provider_payments_refunded` is the real over-refund guard**, not the aggregate's check; the
  application checks under a row lock only to turn a constraint violation into a readable 422.
- **It cannot send refund callbacks, and that has changed meaning.** When ADR-017 was written there
  was no receiver, so queueing nothing was correct. Refund's callback route now exists, so this is
  the reason the one hand-signed HMAC request in the Postman collection and the test suite still
  has to be hand-signed. Closing it means a target URL on `provider_outbound_callbacks`, a refund
  body writer, and a migration.

### Cross-cutting — `com.paymesh.shared`

`MerchantId` (the tenant identifier every capability carries), the `Clock` bean,
`ApiErrorResponse`, the security layer (`SecurityConfiguration`, `AuthenticatedCaller`,
`AuthenticatedCallers` and the argument resolver built on it), and:

**Event delivery** — `com.paymesh.shared.outbox`, ADR-016. The half of the outbox pattern that
did not exist until this session.

| Piece | What it does |
|---|---|
| `PublishOutboxEventsService` | One pass: claims `published_at IS NULL` oldest-first (bounded), dispatches, stamps. A plain object; `OutboxRelay` is the `@Scheduled` bean and holds one call and one log line |
| `EventDispatcher` | Handlers indexed by event type. **One transaction per (handler, event)**, holding the inbox claim and everything the handler writes |
| `ProcessedEventRepository` | `INSERT … ON CONFLICT DO NOTHING`; the row count is the answer. No read, so there is no read-then-write window |
| `EventHandler` | The consumer contract: envelope in, `Map` payload, must be idempotent, must throw to retry, **must not open a transaction** |

Properties worth not breaking:

- **The mapping from row to envelope happens INSIDE the per-item try/catch**, which is the
  one thing open item 2 says both sweeps get wrong. `OutboxReader.findUnpublished` returns raw
  unvalidated rows for exactly this reason. Verified by moving it out: one corrupt row then
  killed the whole pass *and* a different test in the same class, which is open item 2's
  pathology reproduced live.
- **A failed event blocks its own aggregate for the rest of the pass, and nothing else.**
  Without that, the first failure delivers an aggregate's second event before its first.
- **The `published_at` stamp commits separately from the handlers**, which is what makes
  delivery at-least-once. Not a defect to remove: a consumer that will one day live in another
  process cannot share a transaction with the relay at all.
- **Per-handler transactions, not per-event.** One transaction across every consumer would mean
  the Ledger failing rolls back Order's committed work *and* the inbox row recording it, so both
  re-apply.
- **`published_at` is deliberately unmapped on the JPA entity and both relay queries are
  native.** The entity is `@Immutable`, so Hibernate would silently drop an assignment to a
  mapped field — it would look correct and do nothing.
- **Three independent mechanisms stop a payment being applied twice** — the inbox row, the
  consumer's `PENDING` re-check, and `Order.markPaid`'s refusal — and this was *measured*:
  removing the first two together still left the end-to-end test green. So the inbox is proved
  by a test with a guard-free handler that counts invocations, not by an order-level assertion.
  Same shape as the idempotency-filter note below: a partial sabotage that stays green means the
  sabotage was unfaithful.

**Idempotency** — `com.paymesh.shared.idempotency`, ADR-009. A servlet filter running
*after* Spring Security, keyed on `merchant + endpoint + Idempotency-Key`, with
PostgreSQL as the durable authority (SDD §23.1–23.2).

| Situation | Result |
|---|---|
| Header missing on a registered route | `400 IDEMPOTENCY_KEY_REQUIRED` |
| Same key, different body hash | `409 IDEMPOTENCY_KEY_REUSED` |
| Same key, record `COMPLETED` | Stored response replayed, `Idempotency-Replayed: true` |
| Same key, record `IN_PROGRESS` | `409 REQUEST_IN_PROGRESS` |
| Handler threw, or 5xx | Record **deleted** — a retry must be a real retry |

**The insert commits in its own transaction before the handler runs, and that commit
is the entire concurrency control** — the database picks the winner on the primary
key. It is not a read-then-write, and the regression test was verified by making it
one: four simultaneous retries of one key produced four handler executions
(`expected: 1 but was: 4`). Note that a *partial* sabotage — adding a read but leaving
`ON CONFLICT DO NOTHING` with the row count checked underneath — does **not** fail the
test, because the database is still arbitrating. Only discarding the row count breaks
it.

Hashing is over raw request bytes, so semantically identical JSON with different key
order hashes differently and yields a 409. Deliberate: canonicalising means parsing
attacker-controlled JSON before the dedup decision, and a normalisation bug there
replays the *wrong* response. Failing closed on a spurious 409 is strictly safer.

Routes are opt-in via `IdempotentRoutes`; the layer is inert until a route registers.
Four routes are registered today, all in `IdempotencyConfiguration`: Order's two writes
and Payment's two. `POST /api/v1/merchants` stays out because it is unauthenticated and so
has no merchant to scope a key by; `POST /api/v1/customers` stays out because it creates
no financial effect.

---

## How to verify it

```bash
cd backend
./mvnw test                     # 501 tests; needs Docker, no local database
./mvnw spring-boot:run          # port 8080, activates the dev profile via the pom

# API contract, end to end, including cross-tenant isolation and idempotency
npx newman run docs/api/postman/paymesh.postman_collection.json \
  --env-var baseUrl=http://localhost:8080
```

Tests use Testcontainers and never touch a developer database. Flyway migrates an
empty container on every run, so the migrations are re-proved rather than assumed.
The Postman collection's folders must run top to bottom — onboarding creates a merchant,
Identity & Auth attaches a user and captures a token, and Authenticated access, Orders,
Outbox and Payment Intents all use it. Payment Intents runs against the linked order the
Orders folder leaves `PENDING`, not the one it cancels.

**Nothing activates the `dev` profile by default, and that is the whole point.** Each
supported launch path turns it on differently: `./mvnw spring-boot:run` via the
`<profiles>` block in `pom.xml`, the IDE via the shared `BackendApplication [dev]`
configuration in `.run/`, and the test suite via `@ActiveProfiles("dev")`. Running the
packaged jar activates nothing, so it needs `PAYMESH_SECURITY_JWT_SECRET` (32+ bytes),
`SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` supplied explicitly.

A startup failure reading `Property: paymesh.security.jwt.secret / Reason: must not be
blank` means the profile is not active. That is the guard working. It was also, for a
while, a genuine papercut: the IDE run button has no idea the Maven plugin exists, so
a fresh clone failed with a message whose Action line never mentions profiles at all.
The `.run/` configuration exists so the button works rather than failing more
legibly (#28). See README §Running it locally.

The collection is not decorative: dropping the tenant predicate in
`JpaOrderRepository` turns 9 of its assertions red, led by the cross-tenant 404 checks.

---

## Decisions on record

| ADR | Decision |
|---|---|
| 001 | Start as a modular monolith; extract services only after contracts are proven |
| 002 | Package by feature, not by layer |
| 003 | Opaque prefixed identifiers (`mrc_`, `usr_`, `cus_`, `ord_`, `pmt_`) |
| 004 | Domain aggregate and JPA entity are separate types with a hand-written mapper |
| 005 | Integration tests run against Testcontainers, not a developer database |
| 006 | Customer PII encryption deferred; schema already in the encrypted shape |
| 007 | Authentication at the filter chain, tenancy next to the data |
| 008 | Cross-module reads go through a port owned by the consumer |
| 009 | Public-write idempotency in PostgreSQL; records deleted on 5xx |
| 010 | Transactional outbox in PostgreSQL, written in the caller's transaction (its "no relay" section is superseded by 016) |
| 011 | One live payment intent per order, enforced by a partial unique index |
| 012 | Provider callbacks deduplicated by `(provider, external_event_id)` and ordered by a monotonic clock |
| 013 | Guard the confirm transition against an order cancelled underneath it |
| 014 | Expire orders, but never one holding a live collection |
| 015 | Time a stranded `PROCESSING` payment out to `FAILED`, with the risk stated |
| 016 | Deliver events in-process on a broker-shaped consumer contract, before Kafka |
| 017 | Simulate providers through scheduled, signed callbacks — never an inline call |
| 018 | Post the ledger from events, and keep its invariants in the database |
| 019 | Refunds own their callback route, and over-refund is guarded by a lock and a trigger |
| 020 | Defer federated login until there is an identity provider |
| 021 | Make the lifecycle states reachable, and enforce them |
| 022 | Authenticate machines with merchant API credentials |
| 023 | Finish the lifecycle claims, and give the token table a writer |
| 024 | Disabling people, at the two scopes that mean different things |

Note that the SDD's Appendix D has its own ADR list with the same numbers and
different decisions. When citing one, say which source you mean.

---

## Open items, worst first

_Items 1 and 2 of the previous list are now **closed in code** and kept below only where a
residue survives. The Payment module is feature-complete; what follows is what is known to be
wrong with it, worst first, and every one of these was found by review rather than by a
failing test._

0. ~~**`UserStatus.SUSPENDED` and `CLOSED` are still unreachable.**~~ **CLOSED by ADR-024.** The
   question turned out to be two questions: a merchant admin revokes a user's roles at their own
   merchant (the departed-employee case, account survives), and platform staff suspend the account
   platform-wide. Conflating them would have let merchant A lock somebody out of merchant B.
   **Every lifecycle enum in the platform is now reachable.**

   Two things ADR-024 surfaced and did NOT close, recorded here rather than left for the next
   audit: **granting a user a role needs no consent from that user** (grant-by-id rather than an
   invitation, and it leaks existence where revoke deliberately does not), and **platform-scoped
   writes cannot be made idempotent** because an idempotency record is merchant-scoped and
   foreign-keyed to `merchants`. Also still open: `GET /v1/customers` (SDD 10.3's search).

1. **Timing a stranded payment out can kill the order it exists to rescue.** ADR-015 says
   `FAILED` releases the slot so the merchant can retry. But the intent has been stranded for
   at least an hour by then, so an order that set `expires_at` is usually past it. The expiry
   sweep runs within five minutes, finds a `PENDING` order past its deadline with no live
   intent, and expires it. `EXPIRED` is not payable, so create returns 422, and
   `uq_orders_merchant_ref` stops the merchant recreating the order under the same reference.
   The same dead end open item 1 used to describe, reached through its fix. Neither ADR-014
   nor ADR-015 notices. A grace period after a system-initiated terminal transition is the
   likely answer. Only bites orders that set a deadline.
2. **One unmappable row disables a sweep permanently and silently.** Both sweeps run their
   candidate query — which maps every row through the aggregate — *outside* the per-item
   try/catch that exists to stop one bad row killing the run. A row that fails to map throws
   out of `sweep()` entirely, has the oldest timestamp, sits at the head of every batch, and
   the scheduler keeps rescheduling it. Reaching it needs database state the current CHECKs
   forbid, so this is a latent trap rather than a live bug. Move the mapping inside the catch.
3. **ADR-014's race guard depends on `READ COMMITTED` and nothing says so.** The expiry sweep
   takes the order's row lock and then does an *unlocked* read of `payment_intents`. It sees
   an intent committed while it waited on the lock only because each statement takes a fresh
   snapshot. Set `default_transaction_isolation = repeatable read` and it would miss the
   intent and expire an order being collected against — the one thing ADR-014 exists to
   prevent. The isolation level is load-bearing and undocumented.
4. **A provider callback row is never read back, and one line depends on that.**
   `ProviderCallbackJpaEntity.isNew()` always returns `true`, which is necessary — without it
   Spring Data merges and a duplicate delivery becomes a silent `UPDATE` that answers
   `APPLIED`. It stops being correct the moment anything reads a callback row and saves it.
   Reconciliation is exactly that, and no test guards the assumption.
5. **An order can still be cancelled out from under a live payment intent.** The dangerous
   half is closed — confirm re-reads payability inside its transaction, so that intent can
   never collect (ADR-013) — but the inconsistency remains: a `CANCELLED` order can hold a
   live intent until a sweep or a merchant releases it. Deliberate. Closing it entirely needs
   either Order to know Payment exists (forbidden) or a database trigger (hides business logic
   where no reader looks).
6. **The error dispatch renders Boot's error body, not the house shape.** Side effect of the
   fix in #39: the status is now right where it used to be a wrong `401`, but the body is
   `{timestamp,status,error,path}` rather than `{code,message,fieldErrors}`. Belongs with the
   existing RFC-7807 divergence.
7. **Two Postman folders have never been run — and the simulator's is now one of them.** The
   provider-callback folder's HMAC pre-request script encodes the same contract the server-side tests
   prove; the new Provider Simulator folder is in the same position. Both need a live server plus
   `newman`, and the simulator's last six requests additionally need
   `PAYMESH_SIMULATOR_DISPATCH_ENABLED=true`, because `./mvnw spring-boot:run` activates `dev`, which
   switches the dispatcher off. The Java tests cover the same ground and are run; the collections are
   documentation that has not been executed.
8. **One global provider callback secret**, and now a second simulator key beside it. Anyone holding
   the callback secret can name any merchant's intent; anyone holding the simulator key can make the
   provider collect one. A documented deferral until per-provider credentials exist — ADR-017 §3
   argues the split moves this closer rather than further, because the signing secret is now read at
   exactly one place.
9. **`POST /api/v1/merchants` is unauthenticated by design** and has no rate limit.
   It is the obvious abuse vector: an open write endpoint that creates rows.
10. ~~**Authorization is binary per tenant.**~~ **CLOSED by ADR-021.** `AuthenticatedCaller` now
    carries the role instead of discarding it; `MERCHANT_USER` can no longer do what
    `MERCHANT_ADMIN` can. The remaining hole is narrower: role checks are applied where they have
    been written, and only the merchant module has them so far. Original text: holding any role at a merchant grants
   everything at that merchant. `MERCHANT_ADMIN` vs `MERCHANT_USER` matters as soon
   as two endpoints differ by permission.
11. **Access tokens cannot be revoked before expiry** — nothing checks a denylist, so
   the 15-minute lifetime *is* the revocation window. Acceptable now; revisit when a
   compromised session has to be killed immediately.
12. **Customer PII is plaintext** (ADR-006). Needs key management before it holds
   anything real.
13. **A stranded `IN_PROGRESS` idempotency record wedges that key permanently.** If the
   process dies between the insert and the completion update, the row survives with no
   TTL, no age check and no reaper. The endpoint answers (409, no hang), and the
   merchant's escape is a fresh key — backstopped by `merchant_order_ref`, which is
   precisely why those two dedup rules stay independent. But the cost is a wedged key,
   not merely table growth.
14. **A permanently failing event freezes its own aggregate forever, silently.** _(This
   replaces the old item 14, "the outbox has no relay" — closed by ADR-016.)_ The relay retries a
   failed event at the head of every pass and defers that aggregate's later events behind it, so
   ordering holds and the rest of the platform drains. But there is **no dead-letter table, no
   attempt counter and no alert** — only a WARN per pass and a growing
   `min(occurred_at) where published_at is null`, which is precisely SDD §24's metric and needs
   observability that does not exist. This is the largest known hole in event delivery.

   Smaller residue from the same change: **delivery is asynchronous**, so a merchant polling
   immediately after a capture may read `PENDING` for a second or two; `occurred_at` is not
   unique, so two events for one aggregate at the same instant have no defined order (the same
   trade ADR-012 accepts, with the same fix — a sequence number); neither `outbox_events` nor
   `processed_events` is ever pruned; and there is one relay instance with no leader election
   (two would be safe, the inbox arbitrates, but wasteful). **Merchant and Customer still emit no
   events at all**, and `order.paid`, `order.partially_paid`, `order.created`, `order.cancelled`,
   `order.expired`, `payment.created`, `payment.cancelled`, `payment.failed`,
   `payment.authorized` and `payment.requires_action` are all delivered to nobody — dispatched to
   an empty handler list and stamped published, which is the correct handling of an event nobody
   wants.
15. **Smaller:** `DevelopmentSecretGuard` surfaces as a raw stack trace rather than the
    tidy `APPLICATION FAILED TO START` block a `FailureAnalyzer` would give it;
    `ModuleBoundaryTest` allowlists by *filename* rather than path, so a
    `OrderConfiguration.java` created under `order/application` would pass;
    `java-coding-conventions.md` §7 says business-rule failures live in `application`
    without acknowledging that an aggregate-thrown exception must live in `domain` or
    the dependency direction inverts; the idempotency filter's several-merchants branch
    is untested and its replay hard-codes `Content-Type: application/json`;
    `JwtSecretGuards` imports the guard directly, so the suite would not notice if it
    stopped being component-scanned; `IdentityConfiguration`'s javadoc credits
    `MerchantConfiguration` for the `Clock` bean (it is `SharedConfiguration`); the
    customer API's `@Email` rejects a padded address where merchant's tolerates one;
    `PLATFORM_ADMIN` and `SERVICE_ACCOUNT` exist in the enum but are not grantable
    (`user_roles.merchant_id` is `NOT NULL`); writes use `saveAndFlush`, which costs
    one `SELECT` before each `INSERT`; `rest-api-conventions.md` prescribes 422 for
    validation failures where the code returns 400.

---

## What comes next

### Phase 1 is done; what is left is operational

**Payment is feature-complete.** Create, attach, confirm, provider callbacks (deduplicated
and ordered), manual capture and cancel are all built, alongside Order's state history, the
expiry sweep, the `PROCESSING` timeout and the abandoned-checkout sweep. **Every state in the
intent enum is now reachable** — `PARTIALLY_REFUNDED` and `REFUNDED` were the last two, declared
in V8 and produced by nothing until Refund landed.

**Phase 1 is complete.** Every capability the SDD lists for it is built.

**The next PR should be reconciliation, not a Phase 2 capability.** Three separate places now
depend on a job that does not exist:

- A refund whose callback never arrives stays `PROCESSING` **forever**, holding its amount against
  the captured total so the merchant cannot refund the rest. Payment has a sweeper for exactly this
  shape (ADR-015); Refund has none. This is the worst of the three, because it silently reduces
  what a merchant can do.
- `GET /sim/v1/reconciliation/{date}` produces the provider-truth file ADR-015's recovery leans on,
  and nothing reads it.
- An event that fails to deliver is retried forever with no dead-letter and no alert (open item 14).

Three things were waiting on the simulator, and it is worth being precise about which of them it
actually closed, because the temptation to overclaim is real:

- **Reachability: closed.** Every provider-driven state — `SUCCEEDED`, `FAILED`, `AUTHORIZED`,
  `REQUIRES_ACTION`, and the stranded `PROCESSING` that ADR-015's sweeper exists for — is now
  reachable from outside, deterministically, with no hand-signed request. So are all four callback
  outcomes PayMesh can answer.
- **Reconciliation: not closed, but unblocked.** `GET /sim/v1/reconciliation/{date}` produces the
  input ADR-015 leans on. The *job* that compares the provider's truth to PayMesh's and repairs a
  divergence does not exist. This removed the reason it could not be written; it did not write it.
- **A provider sequence number: not closed.** The simulator could emit one, but nothing reads it —
  the field would have to be added to `payment_attempts`, the request record, `ProviderEvent` and the
  guard, all of which live in the module the simulator may not import. That is Payment's PR.
- **Per-provider credentials: still open**, and argued in ADR-017 §3 to be closer rather than
  further, since the signing secret is now read at exactly one place.

The Ledger stays last to be extracted, and now both posts and reverses. Its immutability
triggers turned out to do exactly the job ADR-018 claimed for them: when Refund arrived there was
no "undo a posting" operation to reach for, so the correction could only be a new journal in the
opposite direction. The design forced the right answer rather than relying on discipline.

**One caveat worth carrying forward from ADR-019 §4.1.** The deferred-constraint-trigger pattern
V15 introduced has a sharp edge that was found the hard way: a constraint trigger's query runs on
the snapshot of the statement that queued it, so it *cannot* see a concurrently committed row.
V15's balance trigger is unaffected — a journal's entries are all written by one transaction — but
V16's over-refund trigger was not, and needed a row lock beside it. Anywhere this pattern is reused
to check a rule across rows written by *different* transactions, the trigger alone is not enough.

**Event delivery paid for itself exactly as ADR-016 predicted.** The consumer contract was
deliberately the one a Kafka consumer would need — an envelope in, `processed_events` dedup,
an idempotent handler — so that the Ledger could be written as a second `EventHandler` on
`payment.succeeded` without touching anything shared. It was: the Ledger's consumer is one
class, one `@Bean` in its own configuration, and no change to Order, Payment or `shared`.
Two consumers read `payment.succeeded` and two read `refund.succeeded`, each deduping
independently — which is what `processed_events` being keyed on `(consumer_name, event_id)` rather
than the event alone buys. Payment is now on both sides of the bus, producing one and consuming the
other, and still imports nothing new.

What is still missing on the delivery side is the operational half rather than the mechanism:
no dead-letter, no attempt counter, no "oldest unpublished event age" alert (open item 14).

### Working method that has been effective

- One capability per branch, one focused change per PR, verified live before merge.
- Subagents in isolated worktrees, with migration numbers pre-assigned in the design
  spec so parallel work cannot collide on them. Stacking a dependent branch on an
  unmerged one works, at the cost of mechanical conflicts at merge time.
- **A design spec written and approved before implementation, and corrected when it is
  wrong.** Three spec errors were found by implementers and reviewers this session —
  a single-column FK that could not deliver the isolation the same paragraph promised,
  a testing section that contradicted its own outcome table, and wording that let a
  security guard be built with a real bypass in it. Each was fixed in the spec, not
  just in the code, because the same ambiguity would otherwise recur in Payment.
- **Nothing merges on the author's report.** An independent reviewer re-runs the suite,
  and where a test protects an invariant, breaks the implementation to confirm the
  test catches it. This session that surfaced a packaged jar booting on the published
  signing key, a merge that was textually clean and behaviourally red, and a
  cross-tenant FK hole — none of which any suite reported.
- **Assertions are proved by breaking the code, not by reading them.** A green
  assertion that never fails is worse than no assertion; a passing sabotage means the
  sabotage was unfaithful, not that the code is safe.
- Every non-obvious tradeoff gets an ADR while the reasoning is still fresh.
