# PayMesh — Project Status and Roadmap

_Last updated: 31 July 2026. Update this file at the end of a working session, not
during one._

This is the pick-up-here document. It records what exists, what has actually been
verified, what is deliberately unfinished, and what comes next. For *why* a design
looks the way it does, read the ADRs in `docs/decisions/`; for the target
architecture, read the SDD.

---

## Where the project is

Four of Phase 1's eight capabilities are built and merged: **Merchant**, **Identity &
Access**, **Customer** and **Order**. Underneath them sit two pieces of platform work
that had to land first: the application now refuses to boot on the committed JWT
signing key, and public writes are idempotent through PostgreSQL.

**377 tests, 0 failures** on `main`. Five Flyway migrations (V1–V5). Nine ADRs. Ten
PRs merged (#15–#26). The Postman collection runs **54 requests / 147 assertions**.

The application is still a single deployable with strict module boundaries — the
modular-monolith-first plan from ADR-001 and SDD §30.1. Nothing has been extracted
into a service, and nothing should be until the API and event contracts are proven.

Both items this document flagged as blockers last session are done. Payment can now be
built on an idempotency layer that exists, rather than retrofitted onto one that
doesn't.

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
  `uq_orders_merchant_order_ref` catches a genuine double-submit that arrives with a
  *fresh* key. Neither subsumes the other. Verified by deleting the application's
  pre-check entirely and confirming every integration test stayed green — the
  constraint is the guard, the pre-check only buys a friendlier message.
- **Order reads Customer through a port it owns** (`CustomerLookup`), implemented in
  `order.infrastructure`. Nothing in Order's `api`, `application` or `domain` sees
  Customer. ADR-008.

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
Order's two writes are the only registered routes today.

---

## How to verify it

```bash
cd backend
./mvnw test                     # 377 tests; needs Docker, no local database
./mvnw spring-boot:run          # port 8080, activates the dev profile via the pom

# API contract, end to end, including cross-tenant isolation and idempotency
npx newman run docs/api/postman/paymesh.postman_collection.json \
  --env-var baseUrl=http://localhost:8080
```

Tests use Testcontainers and never touch a developer database. Flyway migrates an
empty container on every run, so the migrations are re-proved rather than assumed.
The Postman collection is **54 requests / 147 assertions** and its folders must run
top to bottom — onboarding creates a merchant, Identity & Auth attaches a user and
captures a token, Authenticated access and Orders use it.

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

Note that the SDD's Appendix D has its own ADR list with the same numbers and
different decisions. When citing one, say which source you mean.

---

## Open items, worst first

1. **`payment_method_tokens` has the flaw Order just fixed.** `V3` gives it two
   single-column foreign keys, so a token can name another merchant's customer. It is
   inert today — nothing maps or writes that table — but the fix is now a one-line
   composite FK, because `uq_customers_merchant_customer` already exists. Do it before
   anything writes payment methods.
2. **`POST /api/v1/merchants` is unauthenticated by design** and has no rate limit.
   It is the obvious abuse vector: an open write endpoint that creates rows.
3. **Authorization is binary per tenant.** Holding any role at a merchant grants
   everything at that merchant. `MERCHANT_ADMIN` vs `MERCHANT_USER` matters as soon
   as two endpoints differ by permission.
4. **Access tokens cannot be revoked before expiry** — nothing checks a denylist, so
   the 15-minute lifetime *is* the revocation window. Acceptable now; revisit when a
   compromised session has to be killed immediately.
5. **Customer PII is plaintext** (ADR-006). Needs key management before it holds
   anything real.
6. **A stranded `IN_PROGRESS` idempotency record wedges that key permanently.** If the
   process dies between the insert and the completion update, the row survives with no
   TTL, no age check and no reaper. The endpoint answers (409, no hang), and the
   merchant's escape is a fresh key — backstopped by `merchant_order_ref`, which is
   precisely why those two dedup rules stay independent. But the cost is a wedged key,
   not merely table growth.
7. **No outbox, no events, no Kafka.** Merchant, Customer and Order all have events
   specified in the SDD that are not emitted. Order's `order.created` / `order.paid`
   and the `outbox_events` table are deferred as one piece of work rather than half-built.
8. **No `order_state_history`** (SDD §11.4). One reachable transition today, carried by
   `cancelled_at` + `cancellation_reason` on the row. Payment brings two more; add the
   table then.
9. **Nothing sweeps expired orders.** `expires_at` and the `EXPIRED` status exist and
   nothing sets the status. Payment will need the sweeper anyway.
10. **Smaller:** `DevelopmentSecretGuard` surfaces as a raw stack trace rather than the
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

**Payment** → **Provider Simulator** → **Ledger** → **Refund**.

Payment is where the difficulty steps up, and it is the reason the last two sessions
went into platform work rather than features. It needs a real state machine
(`REQUIRES_PAYMENT_METHOD → REQUIRES_CONFIRMATION → PROCESSING → SUCCEEDED | FAILED |
REQUIRES_ACTION | AUTHORIZED`), provider callbacks that arrive late, duplicated or out
of order, and idempotency on every write — which now exists and has been proven
end-to-end by Order rather than merely built.

Two things to settle before writing Payment code: whether an order may have more than
one active payment intent (SDD §11.6 says the first version may allow only one), and
whether the outbox lands with Payment or immediately before it. Payment is the first
capability where a lost event has financial consequences, so "before it" is the
likelier answer.

The Ledger is deliberately last in Phase 1 and last to be extracted. It is the
financial source of truth: double-entry, immutable entries, corrections as reversal
transactions rather than edits.

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
