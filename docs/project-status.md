# PayMesh — Project Status and Roadmap

_Last updated: 30 July 2026. Update this file at the end of a working session, not
during one._

This is the pick-up-here document. It records what exists, what has actually been
verified, what is deliberately unfinished, and what comes next. For *why* a design
looks the way it does, read the ADRs in `docs/decisions/`; for the target
architecture, read the SDD.

---

## Where the project is

Three of Phase 1's eight capabilities are built and merged: **Merchant**,
**Identity & Access**, and **Customer**. They run on PostgreSQL behind Flyway
migrations, every endpoint that touches tenant data requires a verified JWT, and
the whole suite runs against throwaway containers.

**197 tests, 0 failures** on `main`. Three Flyway migrations (V1–V3). Six PRs
merged (#15–#20).

The application is still a single deployable with strict module boundaries — the
modular-monolith-first plan from ADR-001 and SDD §30.1. Nothing has been extracted
into a service, and nothing should be until the API and event contracts are proven.

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

### Customer — `com.paymesh.customer`

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/v1/customers` | bearer token | Tenant comes from the token; the request record has no `merchantId` field |
| `GET /api/v1/customers/{id}` | bearer token | Another merchant's customer returns 404 |

`merchant_reference` is unique **per merchant**, not globally. Every index is
composite and merchant-leading. PII is stored in the encrypted *shape* — display
columns that are never queried, separate hash columns carrying the indexes — but is
**plaintext today** (ADR-006).

### Cross-cutting — `com.paymesh.shared`

`MerchantId` (the tenant identifier every capability carries), the `Clock` bean,
`ApiErrorResponse`, and the security layer: `SecurityConfiguration`,
`AuthenticatedCaller`, and the argument resolver that builds one from a verified
token.

---

## How to verify it

```bash
cd backend
./mvnw test                     # 197 tests; needs Docker, no local database
./mvnw spring-boot:run          # port 8080

# API contract, end to end, including cross-tenant isolation
npx newman run docs/api/postman/paymesh.postman_collection.json \
  --env-var baseUrl=http://localhost:8080
```

Tests use Testcontainers and never touch a developer database. Flyway migrates an
empty container on every run, so the migrations are re-proved rather than assumed.
The Postman collection is **28 requests / 69 assertions** and its folders must run
top to bottom — onboarding creates a merchant, Identity & Auth attaches a user and
captures a token, Authenticated access uses it.

---

## Decisions on record

| ADR | Decision |
|---|---|
| 001 | Start as a modular monolith; extract services only after contracts are proven |
| 002 | Package by feature, not by layer |
| 003 | Opaque prefixed identifiers (`mrc_`, `usr_`, `cus_`, `pmt_`) |
| 004 | Domain aggregate and JPA entity are separate types with a hand-written mapper |
| 005 | Integration tests run against Testcontainers, not a developer database |
| 006 | Customer PII encryption deferred; schema already in the encrypted shape |
| 007 | Authentication at the filter chain, tenancy next to the data |

Note that the SDD's Appendix D has its own ADR list with the same numbers and
different decisions. When citing one, say which source you mean.

---

## Open items, worst first

1. **The JWT signing key ships a committed dev default.** There is no profile
   infrastructure, so nothing stops a deployment from silently running on the public
   key — a total authentication bypass. The application must refuse to start on the
   default outside development. *Do this before anything is deployed, and before
   building another service.*
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
6. **No idempotency layer.** SDD §23 requires `merchant + endpoint + Idempotency-Key`
   in PostgreSQL for public writes. Nothing exists yet, and payments cannot be built
   safely without it.
7. **No outbox, no events, no Kafka.** Both Customer and Merchant have events
   specified in the SDD that are not emitted.
8. **Smaller:** the customer API's `@Email` rejects a padded address where merchant's
   tolerates one; `PLATFORM_ADMIN` and `SERVICE_ACCOUNT` exist in the enum but are
   not grantable (`user_roles.merchant_id` is `NOT NULL`); writes use `saveAndFlush`,
   which costs one `SELECT` before each `INSERT`; `payment_method_tokens` exists in
   the schema with no JPA entity; `docs/api/rest-api-conventions.md` prescribes 422
   for validation failures where the code returns 400.

---

## What comes next

### Immediately (do these before new capabilities)

1. **Fail closed on the default JWT secret.** Introduce profiles and refuse to boot
   on the committed key outside development. Item 1 above.
2. **Idempotency for public writes.** `idempotency_records` keyed on
   `merchant + endpoint + Idempotency-Key`, same key + different body → 409. This is
   a prerequisite for Payment, not an optional hardening step, and retrofitting it
   after Payment exists would be far more expensive.

### Then, Phase 1's remaining capabilities, in SDD order

**Order** → **Payment** → **Provider Simulator** → **Ledger** → **Refund**.

Order is the natural next capability and has a clean pattern to copy: domain and
persistence first, merchant-scoped from the token, API behind it. Payment is where
the difficulty steps up — it needs idempotency, a real state machine, and provider
callbacks that arrive late, duplicated, or out of order.

The Ledger is deliberately last in Phase 1 and last to be extracted. It is the
financial source of truth: double-entry, immutable entries, corrections as reversal
transactions rather than edits.

### Working method that has been effective

- One capability per branch, one focused change per PR, verified live before merge.
- Domain and persistence can land ahead of the API when the API needs something that
  does not exist yet — Customer was built this way and it worked well.
- Subagents in isolated worktrees, with migration numbers pre-assigned so they cannot
  collide. Their work is reviewed and re-tested before merge, not merged on report:
  the concurrency bug in refresh rotation was found that way.
- Every non-obvious tradeoff gets an ADR while the reasoning is still fresh.
