# PayMesh

PayMesh is a Payment-as-a-Service backend: multi-tenant merchant onboarding, customers,
orders, payment intents, and — eventually — providers, a double-entry ledger, refunds,
balances, settlements and webhooks. It exists to model the parts of a payment platform
that are genuinely hard (retries, duplicate delivery, tenant isolation, state machines,
auditability) rather than the parts that are CRUD.

It is a single Spring Boot application, Java 21, PostgreSQL, Flyway. Five of Phase 1's
eight capabilities are built.

## What this is not

**PayMesh processes no real money.** It stores no real card details, connects to no
acquirer or card network, and claims no PCI, banking or payment-industry compliance. It
is an educational and portfolio project. Nothing here has been assessed for production
use, and the open items in
[`docs/project-status.md`](docs/project-status.md#open-items-worst-first) are not a
backlog of polish — they include plaintext PII, unrevocable access tokens and an
unauthenticated write endpoint with no rate limit.

## The governing invariant

> A request may fail or be retried, but committed money movement must never be lost,
> silently duplicated, or become unauditable.

Every structural decision below exists to defend that sentence. Four of them are already
in the code, and each is enforced by the database rather than by an application check
that can be bypassed, forgotten, or lost to a race:

| Mechanism | Where | What it prevents |
|---|---|---|
| **PostgreSQL-backed idempotency** | `shared.idempotency`, `idempotency_records` (V4), [ADR-009](docs/decisions/ADR-009-idempotency-for-public-writes.md) | A retried write executing twice. The record is inserted and committed in its own transaction *before* the handler runs; the primary key picks the winner. Records are deleted on 5xx so a retry is a real retry. |
| **Transactional outbox** | `shared.outbox`, `outbox_events` (V7), [ADR-010](docs/decisions/ADR-010-transactional-outbox-in-postgresql.md) | A state change that commits without its event, or an event published for a change that never committed. Both rows go in the caller's transaction. |
| **Composite tenant foreign keys** | `fk_orders_customer`, `fk_payment_method_tokens_customer`, `fk_payment_intents_order` (V5, V6, V8) | A row naming merchant A and a customer or order belonging to merchant B. The FK is on `(merchant_id, customer_id)`, not `customer_id` alone, so PostgreSQL refuses the insert with no application code in the path. |
| **One live intent per order** | `uq_payment_intents_live_per_order` (V8), [ADR-011](docs/decisions/ADR-011-one-live-payment-intent-per-order.md) | Collecting twice for one obligation. A partial unique index over `(merchant_id, order_id)` excluding exactly `FAILED` and `CANCELLED`. Two concurrent creates for one order produce exactly one intent. |

In each case the application also performs a pre-check. The pre-check exists only to
return a readable 409 or 422 instead of a constraint-violation 500 — the integration
tests bypass it and still pass, because the constraint is the guard.

## Current status

Five of Phase 1's eight capabilities are built. **501 tests, 0 failures. Eight Flyway
migrations (V1–V8). Eleven ADRs.**

| Capability | State | What is missing |
|---|---|---|
| **Merchant** | Built | Registration is unauthenticated and unrated-limited |
| **Identity & Access** | Built | Authorization is binary per tenant; access tokens are not revocable before expiry |
| **Customer** | Built | PII is **plaintext** — stored in the encrypted *shape*, not encrypted ([ADR-006](docs/decisions/ADR-006-defer-customer-pii-encryption.md)) |
| **Order** | Built | `PENDING → CANCELLED` is the only reachable transition; no `order_state_history`, no expiry sweeper |
| **Payment** | Core built | Attach payment method, confirm, provider callbacks and manual capture are three more PRs |
| **Provider Simulator** | Not started | — |
| **Ledger** | Not started | — |
| **Refund** | Not started | — |

Platform pieces, honestly:

| Piece | State |
|---|---|
| PostgreSQL + Flyway | Working; Hibernate runs `ddl-auto=validate`, Flyway owns the schema |
| Idempotency | Working, on four registered routes |
| Outbox table | Written in-transaction — **but there is no relay, no Kafka and no consumer, so no event is ever published** |
| Kafka, Redis, rate limiting, API keys, HMAC webhooks | None |
| Observability | `/actuator/health` and `/actuator/info` only |

The unpublished outbox has a visible consequence worth stating up front: **`orders.status`
never reaches `PAID`**, because the consumer that would move it does not exist. That is a
documented inconsistency, not a bug report.

## Architecture

One deployable, strict module boundaries, extract later — the modular-monolith-first plan
from [ADR-001](docs/decisions/ADR-001-start-with-modular-monolith.md) and SDD §30.1.
Nothing has been extracted into a service and nothing should be until the API and event
contracts are proven.

Code is organized **by business capability, not by technical layer**
([ADR-002](docs/decisions/ADR-002-use-package-by-feature.md)). There is no
`com.paymesh.controller` or `com.paymesh.service`. Each capability owns four layers, and
the dependency arrow points inward: `api → application → domain`, with `infrastructure`
implementing `application`'s interfaces.

```text
com.paymesh
├── merchant
│   ├── api              controller, request/response records, @RestControllerAdvice
│   ├── application      use-case services, commands, repository interfaces, exceptions
│   ├── domain           aggregates + value objects, framework-free
│   └── infrastructure   bean wiring (config/) + JPA adapters (persistence/jpa/)
├── identity             + infrastructure/security
├── customer
├── order                + infrastructure/customer   ← the CustomerLookup adapter
├── payment              + infrastructure/order      ← the OrderLookup adapter
└── shared
    ├── api              ApiErrorResponse
    ├── security         SecurityConfiguration, AuthenticatedCaller, argument resolver
    ├── tenant           MerchantId — the tenant identifier every capability carries
    ├── idempotency      filter, records, IdempotentRoutes
    ├── outbox           OutboxEvent, the append port, the JPA adapter
    └── infrastructure   the Clock bean
```

Three conventions carry more weight than their size suggests:

**Beans are wired manually.** Application and domain services are plain `final` classes
with no `@Service`, `@Component`, `@Repository` or `@Autowired`. They are instantiated in
explicit `@Bean` methods inside each capability's infrastructure `@Configuration`. Only
controllers, exception handlers and configurations carry Spring annotations. The domain
and application layers are therefore testable as ordinary Java, with no context to boot.

**Cross-module reads go through a port the consumer owns**
([ADR-008](docs/decisions/ADR-008-cross-module-reads-through-a-consumer-owned-port.md)).
Order does not depend on `GetCustomerService`; it declares a `CustomerLookup` interface
in its own package and implements it in `order.infrastructure`. Payment reads Order the
same way through `OrderLookup`. `ModuleBoundaryTest` asserts both directions —
including that Order never imports Payment.

**The request/domain/response separation is not optional.** `CreateOrderRequest` (API
record, boundary validation) → `CreateOrderCommand` (application record) → `Order`
(domain, owns normalization and invariants) → `OrderResponse` (API record). A request
record is never a persistence type, and a controller never returns a domain object. The
JPA entity is a separate hand-mapped type
([ADR-004](docs/decisions/ADR-004-separate-persistence-model-from-domain-model.md)).

Public identifiers are opaque and prefixed — `<prefix>_<uuid>`, validated in the compact
constructor of a value-object record
([ADR-003](docs/decisions/ADR-003-use-opaque-prefixed-public-identifiers.md)). `mrc_`,
`usr_`, `cus_`, `ord_`, `pi_`. Sequential database keys are never exposed.

### The write path

```mermaid
flowchart TD
    A["POST /api/v1/payment-intents<br/>Authorization + Idempotency-Key"] --> B[Spring Security filter chain]
    B -->|"401 if no valid JWT"| Z1[reject]
    B --> C["IdempotencyFilter<br/>(runs after security)"]
    C -->|"400 key missing · 409 key reused<br/>409 in progress · replay if COMPLETED"| Z2[short-circuit]
    C --> D["Controller<br/>caller.requireSingleMerchant()"]
    D --> E["Application service<br/>every query scoped by merchantId"]
    E --> F["OrderLookup port<br/>reads Order, tenant-scoped"]
    F --> G["One transaction"]
    G --> H["payment_intents row"]
    G --> I["payment_state_history row"]
    G --> J["outbox_events row"]
    H --> K["Database constraints have the last word:<br/>composite tenant FKs, partial unique index"]
    I --> K
    J --> K
```

Authentication is answered at the filter chain; tenancy is answered next to the data
([ADR-007](docs/decisions/ADR-007-enforce-authentication-and-tenant-scoping.md)). The
edge cannot decide whether a caller may touch a row, because it cannot see the row. Every
repository query carries `merchant_id`, and a cross-tenant read returns `404`, never
`403` — a `403` would confirm the id exists and turn the endpoint into an enumeration
oracle.

## The API

All routes are under `/api/v1`. Money is an integer in minor units with an explicit
currency; timestamps are UTC ISO-8601; enum values are `UPPER_SNAKE_CASE`.

### Merchant

| Method | Path | Auth | `Idempotency-Key` | Notes |
|---|---|---|---|---|
| `POST` | `/merchants` | public | — | Self-service onboarding; precedes having an account. `201`. |
| `GET` | `/merchants/{merchantId}` | bearer | — | Caller must hold a role at that merchant, else `404`. |

### Identity & Access

| Method | Path | Auth | `Idempotency-Key` | Notes |
|---|---|---|---|---|
| `POST` | `/auth/register` | public | — | Optional `merchantId` grants `MERCHANT_ADMIN` scoped to it. |
| `POST` | `/auth/login` | public | — | 15-minute HS256 access token + 30-day opaque refresh token. |
| `POST` | `/auth/token/refresh` | refresh token | — | Rotates. Reuse revokes the whole token family. |
| `POST` | `/auth/logout` | refresh token | — | Idempotent; revokes the family. |

Login is deliberately not an oracle: unknown email and wrong password return
byte-identical bodies, an unknown email still runs one BCrypt verification against a
fixed hash so timing does not differ, and account status is checked only *after* the
password verifies — which is why `USER_NOT_ACTIVE` is `403` and not `401`.

### Customer

| Method | Path | Auth | `Idempotency-Key` | Notes |
|---|---|---|---|---|
| `POST` | `/customers` | bearer | — | Tenant comes from the token; the request record has no `merchantId` field. |
| `GET` | `/customers/{customerId}` | bearer | — | Another merchant's customer returns `404`. |

`merchant_reference` is unique per merchant, not globally.

### Order

| Method | Path | Auth | `Idempotency-Key` | Notes |
|---|---|---|---|---|
| `POST` | `/orders` | bearer | **required** | `ord_` id, optional customer link. |
| `GET` | `/orders/{orderId}` | bearer | — | Another merchant's order returns `404`. |
| `GET` | `/orders` | bearer | — | Cursor pagination (`limit`, `cursor`), optional `status` filter. |
| `POST` | `/orders/{orderId}/cancel` | bearer | **required** | Only from `PENDING`, else `409`. Optional reason body. |

`PAID`, `PARTIALLY_PAID` and `EXPIRED` exist in the enum and the CHECK constraint so that
Payment needs no migration on arrival, but no code path reaches them.

### Payment

| Method | Path | Auth | `Idempotency-Key` | Notes |
|---|---|---|---|---|
| `POST` | `/payment-intents` | bearer | **required** | `pi_` id → `REQUIRES_PAYMENT_METHOD`. Amount must equal the order's exactly. |
| `GET` | `/payment-intents/{paymentIntentId}` | bearer | — | Another merchant's intent returns `404`. |
| `GET` | `/payment-intents` | bearer | — | Cursor pagination, optional `status` and `orderId` filters. |
| `POST` | `/payment-intents/{paymentIntentId}/cancel` | bearer | **required** | Only from `REQUIRES_PAYMENT_METHOD`, else `409`. |

All ten statuses are declared in the enum and the CHECK constraint; only
`REQUIRES_PAYMENT_METHOD` and `CANCELLED` are reachable today.

`ORDER_NOT_PAYABLE` is deliberately one error code for three different causes — no such
order, another merchant's order, order not `PENDING`. Splitting them would make the
endpoint an oracle for enumerating another tenant's order ids; an API test compares the
three responses byte for byte.

### Operational

| Method | Path | Auth |
|---|---|---|
| `GET` | `/actuator/health` | public (`show-details: never`) |
| `GET` | `/actuator/info` | public |

The four routes requiring `Idempotency-Key` are registered in one place,
`IdempotencyConfiguration`. Nothing is idempotent by default; an unregistered route
passes through the filter untouched.

## Data model

Flyway owns the schema. Hibernate runs with `ddl-auto=validate` and never creates or
alters a table.

| Migration | Adds |
|---|---|
| `V1__create_merchants.sql` | `merchants`, with `uq_merchants_email` as the real uniqueness guard |
| `V2__create_identity_tables.sql` | `users`, `user_roles`, `refresh_tokens` (SHA-256 hashed, family-tracked), `security_events` |
| `V3__create_customers.sql` | `customers` in the encrypted *shape* — display columns never queried, separate hash columns carrying the merchant-leading indexes — plus `payment_method_tokens` |
| `V4__create_idempotency_records.sql` | `idempotency_records`: state, request hash, stored response, keyed on merchant + endpoint template + key |
| `V5__create_orders.sql` | `orders`, and `uq_customers_merchant_customer` on `customers` so the order → customer FK can be composite and tenant-safe |
| `V6__fix_payment_method_tokens_tenant_foreign_key.sql` | Replaces `payment_method_tokens`' single-column customer FK with the composite `(merchant_id, customer_id)` one — closing the same cross-tenant hole V5 closed for orders, before anything writes the table |
| `V7__create_outbox_events.sql` | `outbox_events` plus a partial index over the unpublished rows a relay will one day poll |
| `V8__create_payment_intents.sql` | `payment_intents`, `payment_state_history` (append-only from day one, so no intent has a hole in its timeline), `uq_orders_merchant_order` to make the order FK composite, and `uq_payment_intents_live_per_order` |

Every merchant-owned table carries `merchant_id`, and every index is composite and
merchant-leading.

## Running it locally

**Prerequisites:** Java 21 and Docker. Docker is needed for the test suite
(Testcontainers) and for a local PostgreSQL if you want to run the application rather
than only test it. No local database installation is required for tests.

```bash
cd backend
./mvnw spring-boot:run          # port 8080
./mvnw test                     # the full suite
./mvnw clean package            # build the jar
./mvnw verify                   # full build + tests
```

### The dev-profile trap

`application.yaml` is committed and therefore ships **no secrets**. It leaves
`paymesh.security.jwt.secret` and the datasource credentials empty on purpose, so a
deployment that forgets to supply them fails at startup rather than silently signing
every access token with a key published in this repository.

The local throwaway values live in `backend/src/main/resources/application-dev.yaml` and
load only when `dev` is the **sole** active profile. **Nothing activates that profile by
default, and each supported launch path activates it differently:**

| How you start it | What activates `dev` |
|---|---|
| `cd backend && ./mvnw spring-boot:run` | The `<profiles>` block on `spring-boot-maven-plugin` in `backend/pom.xml` |
| IntelliJ run button | The shared run configuration `BackendApplication [dev]` in `.run/` |
| `./mvnw test` | `@ActiveProfiles("dev")` on the classes that boot a context |
| `java -jar` | **Nothing.** You supply the values yourself. |

If startup fails with:

```text
Property: paymesh.security.jwt.secret
Reason: must not be blank
```

**the `dev` profile is not active.** That is the guard working, not a misconfiguration.
The failure message's own Action line never mentions profiles, which is exactly why this
is written down: a fresh clone opened in an IDE used to fail this way with no path
forward, and the `.run/` configuration exists so the run button works rather than failing
more legibly.

### Running the packaged jar

No profile is baked into the jar, so a deployment must supply three values:

```bash
PAYMESH_SECURITY_JWT_SECRET=<32+ random bytes> \
SPRING_DATASOURCE_USERNAME=<user> \
SPRING_DATASOURCE_PASSWORD=<password> \
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

Do **not** copy the secret out of `application-dev.yaml`. It is public, and
`DevelopmentSecretGuard` refuses to start on it whenever `dev` is not the only active
profile — including `dev,production`, which is how layered configuration usually goes
wrong.

## Testing

**501 tests, 0 failures.** They need Docker and never touch a developer database:
integration tests run against a throwaway PostgreSQL container
([ADR-005](docs/decisions/ADR-005-use-testcontainers-for-integration-tests.md)), so
Flyway migrates an empty database on every run and the migrations are re-proved rather
than assumed.

```bash
cd backend
./mvnw test
./mvnw test -Dtest=OrderTest                      # one class
./mvnw test -Dtest=OrderTest#cancelsPendingOrder  # one method
```

Domain and application tests are plain JUnit with no Spring context. `@SpringBootTest`
and `MockMvc` are reserved for the API layer. Test names state behavior
(`rejectsRegistrationWhenBusinessNameIsBlank`), never `test1`.

### Assertions are proved by breaking the implementation

This is the standard the project actually holds itself to, and it is the most distinctive
thing about how the code is built.

**A green assertion that has never failed is not evidence.** It is a line of code that
compiles. So where a test protects an invariant, the invariant is verified by *sabotaging
the implementation and confirming the test goes red* — and a sabotage that stays green
means the sabotage was unfaithful, not that the code is safe. Nothing merges on the
author's report; an independent reviewer re-runs the suite and performs the break.

Examples of assertions that carry a recorded break:

| Invariant | The sabotage | What it produced |
|---|---|---|
| Idempotency is decided by the database, not a read-then-write | Turn the commit-first insert into a read-then-write | Four simultaneous retries of one key ran the handler four times: `expected: 1 but was: 4` |
| One live payment intent per order | Downgrade `uq_payment_intents_live_per_order` to a non-unique index | Two concurrent creates for one order: `expected: 1L but was: 2L` |
| Cursor pagination does not skip rows | Remove the `order_id` tiebreak, ordering on `created_at` alone | Three orders at `limit=2`: the third vanishes while every page still looks well-formed |
| Creation is one transaction across three writes | Remove the `TransactionTemplate` wrap | An intent is left behind with no event and no state-history row |
| Tenant scoping is real | Drop the tenant predicate in `JpaOrderRepository` | 9 Postman assertions turn red, led by the cross-tenant `404` checks |
| Cross-tenant orders are refused by PostgreSQL | Raw JDBC insert of an order naming another tenant's customer | Refused by the composite FK, with no application code in the path |

The sabotage results are as informative when they *fail* to fail. A partial sabotage of
the idempotency insert — adding a read but leaving `ON CONFLICT DO NOTHING` with the row
count checked underneath — does **not** turn the test red, because the database is still
arbitrating. Only discarding the row count breaks it. That is recorded too, so nobody
concludes the test covers more than it does.

Some claims cannot be asserted and are verified another way. That "no code path reaches
`PAID`" and "only two payment statuses are reachable" are both verified by grep, not by
assertion, and are labelled as such.

### End-to-end contract

```bash
# against a running application
npx newman run docs/api/postman/paymesh.postman_collection.json \
  --env-var baseUrl=http://localhost:8080
```

Seven folders — Health, Merchant, Identity & Auth, Authenticated access, Orders, Outbox,
Payment Intents — covering cross-tenant isolation, idempotency replay, pagination
boundaries and the `404`-not-`403` rule. They must run top to bottom: onboarding creates
a merchant, Identity & Auth attaches a user and captures a token, and everything after
uses it.

## Design decisions

| ADR | Title | Decision |
|---|---|---|
| [001](docs/decisions/ADR-001-start-with-modular-monolith.md) | Start PayMesh as a modular monolith | One deployable with strict module boundaries; extract services only after the API and event contracts are proven |
| [002](docs/decisions/ADR-002-use-package-by-feature.md) | Organize backend code by business capability | Package by feature with four layers each, never global `controller`/`service`/`repository` packages |
| [003](docs/decisions/ADR-003-use-opaque-prefixed-public-identifiers.md) | Use opaque, prefixed public identifiers | `<prefix>_<uuid>` value objects; a sequential key leaks volume and invites enumeration |
| [004](docs/decisions/ADR-004-separate-persistence-model-from-domain-model.md) | Keep the JPA persistence model separate from the domain model | Two types and a hand-written mapper; `@Entity` on the aggregate would weaken every invariant it protects |
| [005](docs/decisions/ADR-005-use-testcontainers-for-integration-tests.md) | Run integration tests against Testcontainers | A throwaway container per run, so a fresh clone and a CI box both build |
| [006](docs/decisions/ADR-006-defer-customer-pii-encryption.md) | Defer customer PII encryption, ship the schema shape it needs | Encryption is a key-management problem this project cannot yet solve; something that only *looks* encrypted is worse than plaintext |
| [007](docs/decisions/ADR-007-enforce-authentication-and-tenant-scoping.md) | Authentication at the filter chain, tenancy at the data | "Who is calling" is a property of the request; "which rows may they touch" is a property of the row, and the edge cannot see the row |
| [008](docs/decisions/ADR-008-cross-module-reads-through-a-consumer-owned-port.md) | Cross-module reads go through a consumer-owned port | The consumer declares the interface it needs; the adapter lives in the consumer's `infrastructure` |
| [009](docs/decisions/ADR-009-idempotency-for-public-writes.md) | Public-write idempotency in PostgreSQL, records deleted on 5xx | Scope is merchant + endpoint template + key; the pre-handler commit *is* the concurrency control |
| [010](docs/decisions/ADR-010-transactional-outbox-in-postgresql.md) | Domain events to a PostgreSQL outbox, in the caller's transaction, no relay yet | A broker write cannot join a database transaction, so no ordering of the two calls closes the gap |
| [011](docs/decisions/ADR-011-one-live-payment-intent-per-order.md) | One live payment intent per order, enforced by a partial unique index | Reconciling several intents against an order needs a running total that is correct under concurrency, and there is no Ledger yet to hold one |

The SDD's Appendix D has a *separate* ADR list using the same numbers for different
decisions. When citing one, say which source you mean.

## Documentation

| Path | What it is |
|---|---|
| [`docs/project-status.md`](docs/project-status.md) | **The pick-up-here document.** What exists, what is verified, what is deliberately unfinished, what comes next, and the open items worst-first |
| `docs/PayMesh_Payment_as_a_Service_Software_Design_Document.docx` | The SDD: 31 sections plus appendices covering the full ~15-service target platform, per-service API/event/schema catalogs, and end-to-end workflows |
| [`docs/decisions/`](docs/decisions/) | The eleven ADRs above |
| [`docs/api/rest-api-conventions.md`](docs/api/rest-api-conventions.md) | HTTP/JSON contract: versioning, status codes, error shape, pagination, idempotency, money, timestamps, enum casing |
| [`docs/development/java-coding-conventions.md`](docs/development/java-coding-conventions.md) | Layering, DI, immutability, exceptions, logging, testing, framework boundaries, no Lombok |
| [`docs/architecture/package-structure.md`](docs/architecture/package-structure.md) | The package layout in detail |
| [`docs/domain/`](docs/domain/), [`docs/api/examples/`](docs/api/examples/) | Per-capability domain discovery and API contracts |
| [`docs/superpowers/specs/`](docs/superpowers/specs/) | Design specs written and approved *before* implementation, and corrected when found wrong |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Branch and commit conventions |

**The SDD is the target vision; the code is early Phase 1.** The docs deliberately run
ahead of the implementation, and the implementation diverges in places that are known and
recorded — the error body is a flat `{code, message, fieldErrors}` rather than the full
RFC-7807 problem shape, validation failures return `400` where
`rest-api-conventions.md` prescribes `422`, and the merchant JSON id field is `id` rather
than `merchantId`. When extending existing code, match the existing code; when the two
conflict and it matters, surface the divergence rather than silently picking one.

## Roadmap

Payment's core has landed. The rest of it is three PRs, and they are strictly serial
because each one's reachable states depend on the last:

1. **Attach a payment method + confirm** — `V9`, `payment_attempts`
2. **Provider callbacks** — `V10`, `provider_callbacks`, ADR-012
3. **Manual capture**

One thing is settled before the confirm PR, not during it: an order can currently be
cancelled out from under a live payment intent. Today that is an inconsistency because
nothing moves money; the moment confirm exists it becomes a payment collected against a
cancelled order.

Alongside those: `order_state_history`, an order expiry sweeper, and a `PROCESSING`
timeout.

Then, in SDD order: **Provider Simulator** → **Ledger** → **Refund**. The Ledger is
deliberately last in Phase 1 and will be the last thing extracted into a service. It is
the financial source of truth — double-entry, immutable entries, corrections as reversal
transactions rather than edits — and a `SUCCEEDED` payment is only operational state
until the ledger posts.
