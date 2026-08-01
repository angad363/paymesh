# PayMesh — Project Status and Roadmap

_Last updated: 1 August 2026. Update this file at the end of a working session, not
during one._

This is the pick-up-here document. It records what exists, what has actually been
verified, what is deliberately unfinished, and what comes next. For *why* a design
looks the way it does, read the ADRs in `docs/decisions/`; for the target
architecture, read the SDD.

---

## Where the project is

Five of Phase 1's eight capabilities are built: **Merchant**, **Identity & Access**,
**Customer**, **Order**, and the core of **Payment**. Underneath them sit three pieces of
platform work that had to land first: the application refuses to boot on the committed
JWT signing key, public writes are idempotent through PostgreSQL, and a transactional
outbox lets a state change and the event announcing it commit together.

**501 tests, 0 failures.** Eight Flyway migrations (V1–V8). Eleven ADRs. The Postman
collection runs seven folders, the newest covering payment intents.

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
| Order | §11.1–§11.3, §11.6 | Built. **§11.4's `order_state_history` is missing**; §11.5's events are written to the outbox but never published. |
| Payment | §12.1 (partially), §12.2–§12.3, §12.5–§12.6 | Core built. **§12.4 confirm is not implemented**; 2 of the 10 §12.1 states are reachable. |
| Idempotency & concurrency | §23.1–§23.2 | Built in PostgreSQL. §23.3's Redis accelerator: not built, deliberately. |
| Events / outbox | §22.1 outbox shape, §24 durability | Table and in-transaction write only. **§22.2–§22.4 (Kafka topics, contracts, consumers) do not exist.** |
| API Gateway / Edge | §7 | Not built. Its concerns (rate limiting, API keys, HMAC) are absent. |
| Provider Simulator | §13 | Not started — next after Payment. |
| Risk & Fraud | §14 | Not started. |
| Ledger | §15 | Not started — deliberately last in Phase 1. |
| Refund | §16 | Not started. |
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

State machine: `PENDING → CANCELLED` is the only reachable transition today. `PAID`,
`PARTIALLY_PAID` and `EXPIRED` exist in the enum and the CHECK constraint so Payment
does not need a migration on arrival, but **no code path reaches them** — verified by
grep, not by assertion.

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

### Cross-cutting — `com.paymesh.shared`

`MerchantId` (the tenant identifier every capability carries), the `Clock` bean,
`ApiErrorResponse`, the security layer (`SecurityConfiguration`, `AuthenticatedCaller`,
`AuthenticatedCallers` and the argument resolver built on it), and:

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
| 010 | Transactional outbox in PostgreSQL, written in the caller's transaction; no relay yet |
| 011 | One live payment intent per order, enforced by a partial unique index |

Note that the SDD's Appendix D has its own ADR list with the same numbers and
different decisions. When citing one, say which source you mean.

---

## Open items, worst first

1. **An order can be cancelled out from under a live payment intent, and nothing reconciles
   them.** Found in review of the Payment PR and confirmed against PostgreSQL. `Order.cancel`
   checks only that the order is `PENDING` — it has no idea an intent exists, and correctly
   so, because Order must not know Payment exists. Payment reads the order's payability
   *once, outside* the create transaction and never re-checks it. Create the order, create
   the intent, cancel the order: the order is `CANCELLED`, the intent is still live and still
   holds the order's only slot, and a second create is refused `ORDER_NOT_PAYABLE`. The order
   is dead in both directions with no route back through the API. The same state is reachable
   with no cancel at all, as a plain time-of-check-to-time-of-use race.

   Today the damage is an inconsistency, because nothing moves money. **Once attach and
   confirm land, that live intent can reach `SUCCEEDED` against a `CANCELLED` order** —
   collecting for an order the merchant explicitly cancelled. Nothing in the schema, the
   aggregate or the `OrderLookup` port prevents it. It is the mirror of item 2 below and
   neither subsumes the other.

   **Decided (2 August 2026): guard the transition, not the order.** The fix ships with the
   attach+confirm PR and has two halves:

   - **`confirm` re-reads the order's payability inside its own transaction and refuses if
     the order is no longer `PENDING`.** Confirm is the moment money starts moving, so it is
     the transition that has to be defensible; create is not. A stale `payable` read at
     create costs an inconsistency, a stale one at confirm costs a collection.
   - **Create takes a row lock on the order through the `OrderLookup` port**, closing the
     plain time-of-check-to-time-of-use race between the lookup and the insert.

   **Rejected: making `Order.cancel` refuse when a live intent exists.** It closes the hole
   cleanly and it is the wrong shape — Order would have to know Payment exists, which the
   module boundary forbids (ADR-008, design spec §0.5). The consequence of the decision is
   accepted openly: a cancelled order can still be *holding* a live intent, and the intent
   must be cancelled separately. What can no longer happen is that intent succeeding.

   The candidates and their full costs remain written up in the design spec's §7 item 3.
2. **`PROCESSING` is a payment-intent state with no exit, and a stuck intent is a stuck
   order.** An intent holds its order's only live slot (ADR-011). Cancel is refused from
   `PROCESSING` by design, because an in-flight attempt may already have succeeded at the
   provider, so the only way out is a callback that may never arrive. A lost callback
   therefore strands the order and recovery is manual. A `PROCESSING` timeout plus provider
   reconciliation (SDD §21.4, §24.1) is the real answer; the timeout is now in scope (see
   *The remaining scope*), reconciliation is not. This is the mirror of item 1 — that one is
   the order dying under a live intent, this one is the intent dying with the order attached
   — and neither subsumes the other.

   *(This slot previously held "`payment_method_tokens` has the flaw Order just fixed".
   **Resolved** by `V6__fix_payment_method_tokens_tenant_foreign_key.sql` (#30): the
   single-column customer FK is now composite on `(merchant_id, customer_id)`, landed before
   anything writes the table.)*
3. **`POST /api/v1/merchants` is unauthenticated by design** and has no rate limit.
   It is the obvious abuse vector: an open write endpoint that creates rows.
4. **Authorization is binary per tenant.** Holding any role at a merchant grants
   everything at that merchant. `MERCHANT_ADMIN` vs `MERCHANT_USER` matters as soon
   as two endpoints differ by permission.
5. **Access tokens cannot be revoked before expiry** — nothing checks a denylist, so
   the 15-minute lifetime *is* the revocation window. Acceptable now; revisit when a
   compromised session has to be killed immediately.
6. **Customer PII is plaintext** (ADR-006). Needs key management before it holds
   anything real.
7. **A stranded `IN_PROGRESS` idempotency record wedges that key permanently.** If the
   process dies between the insert and the completion update, the row survives with no
   TTL, no age check and no reaper. The endpoint answers (409, no hang), and the
   merchant's escape is a fresh key — backstopped by `merchant_order_ref`, which is
   precisely why those two dedup rules stay independent. But the cost is a wedged key,
   not merely table growth.
8. **The outbox has no relay, so nothing is ever published.** `outbox_events` exists and is
   written in the same transaction as the state change (ADR-010), but there is no Kafka, no
   relay and no consumer, so every row stays unpublished. SDD §24 names that exact state as
   non-corrupting, which is why it was built this way — but it does mean `order.created`,
   `payment.created` and `payment.cancelled` currently go nowhere, and `orders.status` never
   moves when a payment succeeds because the consumer that would move it does not exist.
   Merchant and Customer still emit nothing at all.
9. **No `order_state_history`** (SDD §11.4). One reachable transition today, carried by
   `cancelled_at` + `cancellation_reason` on the row. Payment brings two more; add the
   table then.
10. **Nothing sweeps expired orders.** `expires_at` and the `EXPIRED` status exist and
   nothing sets the status. Payment will need the sweeper anyway.
11. **Smaller:** `DevelopmentSecretGuard` surfaces as a raw stack trace rather than the
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

### Phase 1's remaining capabilities, in SDD order

Payment's core has landed; the rest of it is three more PRs, and they are **strictly
serial** because each one's reachable states depend on the last. In order: **attach a
payment method + confirm** (`V9`, `payment_attempts`), then **provider callbacks** (`V10`,
`provider_callbacks`, ADR-012), then **manual capture**. The design for all three is
already written in `docs/superpowers/specs/2026-08-01-payment-capability-design.md` §3–§5,
with migration numbers pre-assigned so parallel work cannot collide on them.

Both questions this document raised last session are now answered and on the record: an
order may hold **only one** live payment intent (ADR-011), and the outbox landed
**before** Payment rather than with it (ADR-010) — which was the right call, because
Payment's create path needed it on day one.

The question that had to be settled before the confirm PR now is: open item 1, an order
cancelled out from under a live intent. The answer is recorded there — guard the *confirm*
transition by re-reading payability inside its transaction, plus a row lock at create.
Order stays unaware that Payment exists.

### The remaining scope of Phase 1's Payment work, stated exactly

Decided 2 August 2026, so that nothing is discovered mid-PR:

| # | Work | Migration |
|---|---|---|
| 1 | Attach a payment method + confirm, including the open item 1 guard | `V9`, `payment_attempts` |
| 2 | Provider callbacks | `V10`, `provider_callbacks`, ADR-012 |
| 3 | Manual capture | — |
| 4 | `order_state_history` (SDD §11.4), open item 9 | a further migration |
| 5 | Order expiry sweeper (open item 10) | — |
| 6 | `PROCESSING` timeout, so a lost callback cannot strand an order permanently (open item 2) | — |

**Explicitly not in that scope: the outbox relay.** No Kafka, no relay, no consumer. Every
`order.created`, `payment.created`, `payment.cancelled`, `payment.succeeded` and
`payment.failed` row will be written correctly and published nowhere.

The direct consequence, stated so it is never filed as a bug: **`orders.status` will still
never reach `PAID`.** Payment does not write the `orders` table (design spec §0.5) and the
consumer that would move the status does not exist. An order whose payment has succeeded
stays `PENDING` in the API. That is a known, documented inconsistency for the whole of the
remaining Payment work — correct by design, visibly wrong to anyone reading the API, and
resolved only when the relay and an Order consumer land.

After Payment: **Provider Simulator** → **Ledger** → **Refund**. The Ledger is
deliberately last in Phase 1 and last to be extracted. It is the financial source of
truth: double-entry, immutable entries, corrections as reversal transactions rather than
edits.

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
