# ADR-046: Extract the merchant service — the sole emitter of merchant lifecycle

- Status: Accepted
- Date: 2026-10-20
- Scope: PayMesh Phase 3C, PR 11. New `merchant/` module (port 8087), the eighth deployable. No new
  monolith migration (see §2); two migrations in the module's own Flyway history. Follows ADR-038
  (schema-per-service, the `merchant` schema and its fenced `merchant_svc` role), ADR-039 (the
  `merchant_ref` projection this closes the loop on), ADR-040 (the gateway), ADR-041–045 (the
  extraction recipe, with ADR-045 the most recent and the dual-path-by-gateway-re-point precedent).
  Plan of record: `docs/phase-3-microservices-extraction-plan.md` §3C PR 11.

## Context

ADR-039 gave every consumer a local `merchant_ref(merchant_id, status, updated_at)` projection, fed by
`merchant.registered/activated/suspended/closed`. But the **monolith** emitted those events on its own
behalf: `ChangeMerchantStatusService` and `RegisterMerchantService` wrote the `merchants` row and the
outbox event in one transaction, inside the process that also serves every other capability. The
projection is correct, but its authority is still the monolith's. PR 11 makes the service that owns
merchant status the sole producer of the events every projection consumes. That is the loop ADR-039
opened.

## Decision

### 1. Scope: what moves, and what the plan got wrong

The `merchant` schema (ADR-038) holds four tables: `merchants`, `merchant_status_history`,
`api_credentials` and `kyc_submissions`. The merchant service owns all four, and its capability is the
`com.paymesh.merchant` package: registration, lifecycle (activate/suspend/close), KYC review, API
credential issuance and revocation, and the ApiKey authenticator that uses them.

**Divergence from the plan, surfaced rather than silently resolved.** The plan's PR 11 says "merchant +
settlement-config service". The code disagrees. `settlement_configs` lives in the **`settlement`**
schema (ADR-038, the code-package mapping that already corrected the plan's prose once, in PR 3), and
its only API is `/api/v1/settlement-config`, which is not under `/api/v1/merchants`. PR 12 (Settlement)
is the capability that owns that table, so **settlement config does not move in PR 11**. The plan's
wording is stale on this point and is corrected here; PR 12's section should be read the same way.

### 2. Dual path by gateway re-point, and one writer at a time

The shape is ADR-045's, not ADR-044's. The monolith keeps the whole `com.paymesh.merchant.*` subtree,
its `merchant` schema access and its outbox producer, compiled and wired. Rollback is re-pointing the
gateway's `merchant-uri` back at `backend-uri`. There is no mode flag, because no in-process caller
chooses between implementations. There is no monolith migration.

Two processes now *can* write `merchants`, so the switch has to be exclusive. The gateway enforces it:
every `/api/v1/merchants/**` write goes to exactly one of the two, and only the gateway decides which.
The monolith's merchant write routes are unreachable from clients once the route is re-pointed. The
monolith is not a second writer in practice, because the gateway is the only path clients use.

The shared physical database makes rollback data-safe: both processes read and write the same
`merchant` rows, so re-pointing back loses no state. What rollback does **not** undo is events already
published. The cutover runbook must **drain the monolith's outbox** (`published_at` and
`kafka_published_at` set for every `merchant.*` row) before the gateway is re-pointed. Otherwise a
monolith-published `merchant.activated` and a service-published `merchant.suspended` can reach the
projections out of order. This is a stated operational gate, not something the code enforces.

### 3. The event contract does not change

The merchant service's outbox emits the same event types and the same payloads the monolith's
`MerchantLifecycleEvents` emits today: `merchant.registered`, `merchant.activated`,
`merchant.suspended`, `merchant.closed`, and the audited `merchant.status_changed.audited` that
engagement's `RecordMerchantStatusChangeAuditHandler` turns into an `audit_events` row (ADR-043). The
Kafka topic is derived from the aggregate type `merchant` (ADR-036), so every existing consumer keeps
its topic and its consumer group unchanged. No projector changes. Nothing downstream learns that the
producer moved.

`merchant_ref` consumers (six schemas, ADR-039) are therefore unaffected. Their eventual-consistency
lag (ADR-039's accepted suspension lag) is unchanged, and a status change that reaches the merchant
service's outbox reaches the projections on the same path as before.

### 4. The ApiKey authenticator moves with its table

ADR-045 §3 deferred ApiKey→JWT minting until `api_credentials` was reachable. PR 11 resolves it on the
merchant side: the table, the `ApiCredentialAuthenticator`, and the `ApiKeyAuthenticationFilter` that
calls it all move into the merchant service. A merchant service that owns the credentials verifies its
own ApiKeys against its own table, with no cross-service call.

What this does **not** resolve, and is stated here so it is not mistaken for solved: `ApiKey`
authentication on **other** capabilities. Payment, Webhook and Engagement still reach `api_credentials`
only through the monolith's copy. Once Payment extracts (PR 13), an ApiKey presented to a payment route
needs a synchronous verify against the merchant service, or a JWT minted by identity from a verified
key. That choice belongs to PR 13 and is not made here. Until then, ApiKey callers of extracted routes
get the same answer they get today: JWT-only services refuse them, and the gateway fronts them.

### 5. Merchant-detail reads stay with the owner

`GET /api/v1/merchants/{merchantId}` and the other reads are served by the merchant service, which owns
the rows. The plan's "synchronous merchant-detail reads for the few paths that need fresh data" is
**not needed today**: no other capability calls `GetMerchantService`. Every other consumer reads the
local `merchant_ref` projection (ADR-039), and that is the rule this PR preserves. A synchronous
cross-service merchant read is added only when a concrete caller needs fresh data the projection cannot
give, and it is recorded in that caller's ADR, not here.

### 6. Gateway

`merchantRoutes` (`@Order(0)`) forwards `/api/v1/merchants/**` to `paymesh.gateway.merchant-uri`
(`:8087`), rate-limited as client traffic. `@Order(0)` is load-bearing for the same subset reason as
`identityRoutes`: `/api/v1/merchants/**` is a subset of `apiRoutes`' `/api/**`. `POST /api/v1/merchants`
(self-serve registration) stays unauthenticated at the edge, as ADR-040 requires; everything else under
the prefix falls under `anyRequest().authenticated()`. `/api/v1/settlement-config/**` is deliberately
**not** routed here (§1). `GatewayMerchantRouteTest` proves precedence and the public registration pass.

### 7. The merchant gate and the public registration gap

The merchant service enforces the merchant-status rules on its **own** table: no `merchant_ref` projection,
and no projection-backed `MerchantStatusGate` (it owns the status). It keeps the monolith's write refusal
for non-`ACTIVE` merchants, through an `OwnTableMerchantStatusGate` that reads the local `merchants` row.
Lifecycle-transition endpoints stay exempt from the gate, as ADR-021 requires, or suspension is irreversible.

Public self-registration is carried over unchanged. It is the same surface ADR-045 flagged: a
`merchantId` supplied at `POST /api/v1/auth/register` grants `MERCHANT_ADMIN` there, and that is
identity's flow, not this one. The register fix is its own PR, decided before or alongside identity
hardening, and this PR neither depends on it nor changes it.

## Consequences

- The merchant service is the single authority for merchant status, and every `merchant_ref` projection
  consumes its events. This closes ADR-039's loop.
- **Cutover has an operational gate** (§2): the monolith's outbox must be drained before the gateway
  re-points. A runbook step, and the one place the design can still go wrong at deploy time.
- ApiKey authentication is fully local to the merchant service for merchant routes, and still
  monolith-only for every other capability until PR 13 decides it (§4).
- Settlement config stays in the `settlement` schema and moves with PR 12, correcting the plan's wording.
- The `shared` subtree is duplicated into an eighth module until PR 16 makes it a library, the stated
  cost of the recipe.
