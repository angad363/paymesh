# Design: Secret hardening, API idempotency, and the Order capability

_Written 31 July 2026. Status: approved. Supersedes nothing._

Three changes, in dependency order. They close the two blockers named at the top of
`docs/project-status.md` and then build Phase 1's next capability on top of them.

| # | Change | Branch | Migration |
|---|---|---|---|
| 1 | Fail closed on the JWT signing secret | `fix(security)/fail-closed-on-default-secret` | — |
| 2 | Public-API idempotency layer | `feat(shared)/api-idempotency` | `V4` |
| 3 | Order capability | `feat(order)/order-capability` | `V5` |

1 and 2 are independent. 3 depends on 2 having merged, because `POST /api/v1/orders`
is the layer's first real caller.

Everything below assumes the conventions already in force: package-by-feature
(ADR-002), manual bean wiring, request/domain/response separation, opaque prefixed
identifiers (ADR-003), separate JPA entity and domain aggregate (ADR-004),
Testcontainers for integration tests (ADR-005). Where this document is silent, the
existing Customer capability is the pattern to copy.

---

## 1. Fail closed on the JWT signing secret

### The problem

`application.yaml` commits `paymesh.security.jwt.secret: dev-only-insecure-jwt-signing-secret-change-me`.
Nothing forces an operator to override it. A deployment that forgets the environment
variable boots happily and signs every access token with a value published in a public
git history — anyone can mint a token for any user at any merchant. This is a total
authentication bypass, and it is silent.

The same file commits the datasource username and password. Same mechanism, same fix,
so they move together.

### The change

Secrets leave the committed default configuration entirely. `application.yaml` keeps
every non-secret property and declares the secret properties with **no value**.

A new `application-dev.yaml` carries the development values. It is activated
explicitly, never by default:

- `spring-boot:run` activates it through `spring-boot.run.profiles=dev` in the Maven
  plugin configuration in `pom.xml`.
- Integration tests activate it through the existing test configuration.

Absent the `dev` profile, the secret must come from the environment
(`PAYMESH_SECURITY_JWT_SECRET`) or the application does not start.

### Two guards

**Guard one — the property must be present.** A `@ConfigurationProperties("paymesh.security.jwt")`
record replaces the scattered `@Value("${paymesh.security.jwt.secret}")` lookups in
`IdentityConfiguration`, validated with `@NotBlank` on the secret and `@NotNull` on
both TTLs. A missing secret then fails startup with a message naming the property,
rather than surfacing as a `NullPointerException` inside `JwtAccessTokenService`.

**Guard two — the known dev value is refused outside development.** The dev secret is
public, so an operator pasting it into a real environment variable defeats guard one
while achieving nothing. A startup check compares the configured secret against the
known dev constant and, when they match and the deployment is not a development one,
throws with a message that says what to do.

Two details that this wording originally left dangerously vague, both found in review
after a packaged jar was booted successfully on the published key:

- **"Development" means `dev` is the *only* active profile**, not merely one of them.
  `SPRING_PROFILES_ACTIVE=dev,production` is a production deployment. Layered config
  (Helm base + overlay, compose `env_file` + inline) appends profiles rather than
  replacing them, so this is an ordinary operator error, and Spring's
  `Environment.matchesProfiles("dev")` returns true for it.
- **The comparison strips and ignores case.** Spring does not trim environment
  variables, and a Kubernetes secret populated from a file or a Docker `--env-file`
  routinely carries a trailing newline. An exact `equals` reduces the signing key to a
  public string plus one guessable character.

Note when testing this: `ApplicationContextRunner.withPropertyValues` **trims its
values**, so a whitespace case written through it passes against a broken guard and
proves nothing. Drive those cases through a raw `StandardEnvironment` with a
`MapPropertySource`. Implemented as an `ApplicationListener` /
`@Bean` validation in the shared infrastructure package, not inside identity — it is a
deployment rule, not an identity rule.

`JwtAccessTokenService`'s existing 32-byte minimum stays exactly as it is. It is a
different check (key strength, not key provenance) and both are wanted.

### Testing

- Context loads with the `dev` profile active. (Guard one satisfied.)
- Context **fails** to load with no profile and no secret property, and the failure
  message names `paymesh.security.jwt.secret`.
- Context **fails** to load when the dev constant is supplied without the `dev`
  profile.
- Context loads when a different, sufficiently long secret is supplied without the
  `dev` profile.

The last case is the one that proves the guard is not simply "always fail".

### Out of scope

Secret manager integration, key rotation, and a JWKS endpoint. There is no deployment
yet; these become real when there is one.

---

## 2. Public-API idempotency layer

SDD §23.1. The durable scope is **merchant + endpoint + Idempotency-Key**, the
authority is PostgreSQL, and a cache loss must never permit a duplicate financial
write.

### Migration `V4__create_idempotency_records.sql`

```
idempotency_records
  merchant_id      VARCHAR(40)   NOT NULL   -- tenant, from the verified token
  endpoint         VARCHAR(200)  NOT NULL   -- "POST /api/v1/orders" (template, not the concrete URI)
  idempotency_key  VARCHAR(255)  NOT NULL   -- caller-supplied
  request_hash     CHAR(64)      NOT NULL   -- SHA-256 hex of the raw request body bytes
  status           VARCHAR(20)   NOT NULL   -- IN_PROGRESS | COMPLETED
  response_status  SMALLINT                 -- null while IN_PROGRESS
  response_body    TEXT                     -- null while IN_PROGRESS
  created_at       TIMESTAMPTZ   NOT NULL
  completed_at     TIMESTAMPTZ

  PRIMARY KEY (merchant_id, endpoint, idempotency_key)
  CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
  CHECK ((status = 'IN_PROGRESS' AND response_status IS NULL AND completed_at IS NULL)
      OR (status = 'COMPLETED'   AND response_status IS NOT NULL AND completed_at IS NOT NULL))
  FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id)
```

The primary key **is** the scope. There is no surrogate id and no `idm_` prefix,
because nothing outside the server ever names one of these rows — ADR-003 governs
identifiers that appear in an API, and this is not one.

The paired CHECK constraint is what stops a half-written record from being replayed as
if it were a real response.

`endpoint` stores the **path template**, not the concrete request URI. Storing
`/api/v1/orders/ord_abc/cancel` would scope the key per-order, which happens to be
harmless for cancel but is wrong in general; the template keeps the scope
endpoint-shaped as the SDD specifies.

Follow `V3`'s commenting standard: every column and constraint explains *why*, not
*what*.

### Package `com.paymesh.shared.idempotency`

It lives in `shared` for the same reason `SecurityConfiguration` does — it governs
every capability and belongs to none.

| Type | Layer | Purpose |
|---|---|---|
| `IdempotencyRecord` | domain | Immutable record; `status`, `requestHash`, stored response |
| `IdempotencyRepository` | application | Port: `insertIfAbsent`, `findBy`, `complete`, `delete` |
| `JpaIdempotencyRepository` + `SpringDataIdempotencyRepository` + entity + mapper | infrastructure | Adapter, per ADR-004 |
| `IdempotentRoutes` | infrastructure | The `METHOD + path template` set that requires the header |
| `IdempotencyFilter` | infrastructure | The behaviour below |
| `IdempotencyConfiguration` | infrastructure | Manual wiring, `FilterRegistrationBean` |

### Filter behaviour

The filter is registered **after** Spring Security in the chain, because it needs the
authenticated merchant and, per the SDD scope, a request without one has no
idempotency scope to speak of. `POST /api/v1/merchants` is public and therefore
deliberately not covered.

1. Route not in `IdempotentRoutes` → pass through, do nothing.
2. `Idempotency-Key` header missing, blank, or longer than 255 chars →
   `400 IDEMPOTENCY_KEY_REQUIRED`.
3. Read the body into a byte array (the request is wrapped so the handler can still
   read it), hash it with SHA-256, hex-encode.
4. Resolve the merchant from the security context using the same claim parsing
   `AuthenticatedCallerArgumentResolver` already uses. Extract that parsing into a
   shared helper rather than duplicating it — two readers of the same claim that drift
   apart is exactly the bug class this project keeps closing.
5. Attempt `INSERT ... (status = IN_PROGRESS)`.

| Outcome | Response |
|---|---|
| Insert succeeds | Run the handler. Capture status + body. `< 500` → `UPDATE` to `COMPLETED` and return it. `>= 500` or the handler threw → **`DELETE` the record** and return the error unchanged. |
| Conflict, stored `request_hash` differs | `409 IDEMPOTENCY_KEY_REUSED` |
| Conflict, same hash, `COMPLETED` | Replay the stored status and body verbatim, plus header `Idempotency-Replayed: true` |
| Conflict, same hash, `IN_PROGRESS` | `409 REQUEST_IN_PROGRESS` |

**The insert commits in its own transaction before the handler runs.** That commit is
the entire concurrency control: two simultaneous retries of the same key race on the
primary key and the database picks the winner, exactly as refresh-token rotation lets
the `UPDATE` decide. The loser is indistinguishable from a replay and is treated as
one. Do not read-then-write.

**Deleting the record on 5xx is deliberate.** A 500 means the server does not know
what happened; pinning that key to a failure would make a legitimate retry impossible.
A 4xx *is* stored — the caller's request was understood and rejected, and repeating it
deserves the same answer.

Response capture uses Spring's `ContentCachingResponseWrapper`. It is already on the
classpath; do not write one.

Error bodies use the existing flat `ApiErrorResponse.of(code, message)` shape, matching
the rest of the codebase rather than the RFC-7807 shape the conventions doc describes.

### Testing

Unit tests over the filter with a stub repository cover every row of the table above.
An integration test against Testcontainers covers the ones only the database can prove:

- The same key sent **concurrently** produces exactly one execution of the handler, at
  least one `201`, and every other response is either that same `201` replayed or a
  `409 REQUEST_IN_PROGRESS`.
- The same key sent **sequentially** after the first completes produces two identical
  responses, the second carrying `Idempotency-Replayed: true`.
- A record left `IN_PROGRESS` yields `409 REQUEST_IN_PROGRESS`, not a hang.

> **Corrected 31 July 2026.** This section originally asked for two *concurrent*
> requests to produce "two identical responses". That contradicts the outcome table
> above and cannot hold: the loser of the race arrives while the winner is still
> `IN_PROGRESS`, so there is no stored response to replay yet. Identical responses is
> a property of a *sequential* retry, and the two cases are now stated separately. The
> table is normative; where this prose disagreed with it, the prose was wrong.

Write the concurrency test so that it **fails if the insert is changed to
read-then-write**. A test that passes either way proves nothing.

### Out of scope

Redis acceleration (SDD §23.2 makes PostgreSQL the authority regardless), the
expiry/reaper job, and the "wait a short bounded interval" variant of the in-progress
response — a `409` is a correct and simpler answer.

---

## 3. Order capability

SDD §11. An order is the merchant's commercial intent: what is being bought and how
much is due. It does not talk to providers, does not post ledger entries, and does not
own payment attempts.

### Migration `V5__create_orders.sql`

```
orders
  order_id            VARCHAR(40)   NOT NULL   -- "ord_" + UUID
  merchant_id         VARCHAR(40)   NOT NULL   -- FK merchants, leads every index
  customer_id         VARCHAR(40)               -- optional, FK customers
  merchant_order_ref  VARCHAR(100)              -- the merchant's own reference
  amount_minor        BIGINT        NOT NULL
  currency            CHAR(3)       NOT NULL
  amount_paid_minor   BIGINT        NOT NULL    -- 0 until Payment exists
  status              VARCHAR(32)   NOT NULL
  description         VARCHAR(500)
  metadata            JSONB
  expires_at          TIMESTAMPTZ
  cancellation_reason VARCHAR(200)
  cancelled_at        TIMESTAMPTZ
  version             INTEGER       NOT NULL    -- @Version, SDD 23.3
  created_at          TIMESTAMPTZ   NOT NULL
  updated_at          TIMESTAMPTZ   NOT NULL

  PRIMARY KEY (order_id)
  UNIQUE (merchant_id, merchant_order_ref)      -- per merchant, NULLs distinct
  CHECK (amount_minor > 0)
  CHECK (amount_paid_minor >= 0 AND amount_paid_minor <= amount_minor)
  CHECK (currency ~ '^[A-Z]{3}$')
  CHECK (status IN ('PENDING', 'PAID', 'PARTIALLY_PAID', 'CANCELLED', 'EXPIRED'))
  CHECK ((status = 'CANCELLED' AND cancelled_at IS NOT NULL)
      OR (status <> 'CANCELLED' AND cancelled_at IS NULL))

INDEX (merchant_id, created_at DESC)            -- the list endpoint's default page
INDEX (merchant_id, status)
INDEX (merchant_id, customer_id) WHERE customer_id IS NOT NULL
```

`amount_minor` is a positive integer in minor units with the currency held separately —
never a decimal, never a float. The `amount_paid_minor <= amount_minor` check is the
schema-level statement that an order cannot be overpaid.

`metadata` maps to `Map<String, String>` via Hibernate's
`@JdbcTypeCode(SqlTypes.JSON)`. Cap it in the domain (16 keys, 40-char keys, 500-char
values) so a merchant cannot use it as free storage.

The FK to `customers` is what makes the optional customer link real; combined with the
lookup port below, an order can never point at another merchant's customer.

### State machine

```
PENDING ──cancel──▶ CANCELLED
   │
   ├──(payment, later)──▶ PARTIALLY_PAID ──▶ PAID
   └──(expiry, later)───▶ EXPIRED
```

Only `PENDING → CANCELLED` is reachable in this PR. `PAID`, `PARTIALLY_PAID` and
`EXPIRED` exist in the enum and the CHECK because the schema should not need a
migration the moment Payment lands, but **no code path reaches them yet** and none
should be added speculatively.

Callers request `cancel`. The aggregate exposes `order.cancel(reason, at)`, which
throws `OrderNotCancellableException` from any non-`PENDING` state. There is no status
setter.

### API

All four routes are merchant-scoped from the token. No route reads a tenant from a
path, query or body — the property that makes cross-tenant access impossible rather
than unlikely.

| Route | Idempotent | Success | Notes |
|---|---|---|---|
| `POST /api/v1/orders` | yes | `201` | Body: `customerId?`, `merchantOrderReference?`, `amountMinor`, `currency`, `description?`, `metadata?`, `expiresAt?` |
| `GET /api/v1/orders/{orderId}` | — | `200` | Another merchant's order → `404` |
| `GET /api/v1/orders` | — | `200` | `?limit=&cursor=&status=`; envelope `{data, pagination:{limit, nextCursor, hasMore}}` per conventions §27 |
| `POST /api/v1/orders/{orderId}/cancel` | yes | `200` | Body: `reason?`. Non-`PENDING` → `409` |

Cursor is an opaque base64 of `(created_at, order_id)` — the tiebreak matters, or two
orders created in the same millisecond can be skipped or repeated across pages.
`limit` defaults to 20, caps at 100.

Errors, all through a per-feature `OrderExceptionHandler`:

| Exception | Status | Code |
|---|---|---|
| `OrderNotFoundException` | 404 | `ORDER_NOT_FOUND` |
| `OrderReferenceAlreadyExistsException` | 409 | `ORDER_REFERENCE_ALREADY_EXISTS` |
| `OrderNotCancellableException` | 409 | `ORDER_NOT_CANCELLABLE` |
| `CustomerNotFoundForOrderException` | 422 | `CUSTOMER_NOT_FOUND` |
| `NoMerchantScopeException` | 403 | `NO_MERCHANT_SCOPE` |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` |
| `IllegalArgumentException` | 400 | `INVALID_REQUEST` |

The unique constraint on `(merchant_id, merchant_order_ref)` — not an
`existsByMerchantOrderReference` check — is the real guard, and the adapter translates
its violation into the 409, exactly as `JpaMerchantRepository` does for
`uq_merchants_email`. The pre-check may stay for a friendly message; it may not be
trusted.

### The two dedup rules are independent

They catch different mistakes and both are wanted:

- **`Idempotency-Key`** answers "is this the same request I already handled?" Same key
  and same body replays the original `201` — a network retry costs nothing.
- **`merchant_order_ref`** answers "does this merchant already have an order for this
  purchase?" A genuine double-submit that carries a *fresh* key still hits the unique
  constraint and gets `409 ORDER_REFERENCE_ALREADY_EXISTS`.

Neither subsumes the other. A merchant that retries with a new key would create a
duplicate order under the header alone.

### Cross-module read: the customer link

`POST /api/v1/orders` accepts an optional `customerId` and must reject one that does
not exist or belongs to another merchant. That is Order reading Customer, the first
cross-module dependency in the codebase.

Order defines the port **it** needs, in its own package:

```java
// com.paymesh.order.application
public interface CustomerLookup {
    boolean exists(MerchantId merchantId, String customerId);
}
```

The implementation lives in `com.paymesh.order.infrastructure` and delegates to
Customer's `GetCustomerService`. `com.paymesh.order` never imports
`com.paymesh.customer.application` anywhere else.

This is a one-implementation interface, which the project would normally cut. It stays
because it is the module boundary ADR-001 exists to protect: the consumer owns the
contract, so extracting Customer into a service later changes one adapter class rather
than every call site. It is documented as **ADR-008**.

Note the check is advisory — a customer could be deleted between the check and the
insert. The FK is what actually guarantees it, and the check exists to turn a
constraint violation into a readable `422`.

### Testing

Follow the existing split: plain JUnit for domain and application, `@SpringBootTest` +
`MockMvc` for the API, Testcontainers for persistence.

Behaviour that must be covered, stated as the properties they protect:

- An order cannot be created with a zero, negative, or absurdly large amount.
- Currency is normalised to uppercase and rejected if not three letters.
- `merchantOrderReference` is unique per merchant and **not** globally: two merchants
  may both use `ORDER-7788` and both succeed.
- Reading another merchant's order returns `404`, not `403` — existence is not leaked.
- Cancelling twice: the second call is `409 ORDER_NOT_CANCELLABLE` on a fresh
  idempotency key, and a replayed `200` on the same key.
- `POST /api/v1/orders` without an `Idempotency-Key` is `400`.
- The same key with a different body is `409 IDEMPOTENCY_KEY_REUSED`.
- A `customerId` belonging to another merchant is rejected, and the rejection does not
  reveal that the customer exists.
- Listing pages correctly across a boundary where two orders share a `created_at`.

### Deliberately deferred

- **`order_state_history` (SDD §11.4).** One reachable transition today;
  `cancelled_at` and `cancellation_reason` on the row carry it. Add the table when a
  third transition lands — Payment will bring two.
- **`outbox_events`, `order.created`, `order.paid` (SDD §11.5).** No Kafka, no relay,
  no consumer. Roadmap item 7 covers the whole outbox story at once; half of it now
  would be scaffolding.
- **Expiry sweeping.** `expires_at` and the `EXPIRED` status exist; nothing schedules a
  sweep. An expired-but-`PENDING` order is currently only wrong in the reporting sense,
  and Payment will need the sweeper anyway.
- **`PARTIALLY_PAID` / `PAID` transitions.** They belong to the `payment.succeeded`
  consumer, which does not exist.

---

## Sequencing and review

1 and 2 run in parallel in isolated worktrees. 3 starts after 2 merges. Migration
numbers are assigned in this document precisely so two agents cannot collide on `V4`.

Every branch is reviewed against this spec and re-tested before merge, not merged on
the author's report. The refresh-token concurrency bug was found that way and the
practice stays.

Each PR updates, in the same PR:

- `docs/api/postman/paymesh.postman_collection.json` — new requests with assertions
  that actually assert, in a folder that runs top to bottom.
- `docs/decisions/` — ADR-008 for the cross-module port, ADR-009 for the idempotency
  design (the delete-on-5xx rule and the PostgreSQL-as-authority choice both need a
  written reason).
- `docs/project-status.md` — at the end of the session, not during.
