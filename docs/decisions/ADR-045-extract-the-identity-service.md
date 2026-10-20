# ADR-045: Extract the identity service — issuance moves, validation stays shared

- Status: Accepted
- Date: 2026-10-08
- Scope: PayMesh Phase 3B, PR 10. New `identity/` module (port 8086), the seventh deployable. No new
  monolith migration (see §2); two migrations in the module's own Flyway history. Follows ADR-038
  (schema-per-service), ADR-040 (the gateway and its edge JWT validation), ADR-041 (the extraction
  recipe), ADR-042/ADR-043 (the leaf extractions this reuses wholesale), ADR-044 (the dual-path
  property-flip precedent), and revisits the forward-looking note in ADR-007/ADR-040 and
  `JwtAccessTokenService`'s own javadoc about the day an independent service verifies tokens it cannot
  mint. Plan of record: `docs/phase-3-microservices-extraction-plan.md` §3C PR 10.

## Context

Identity is the service every other service depends on, but not in the way that phrase usually means.
Nothing calls identity to *validate* a token: validation is already distributed. The gateway rebuilds
the HS256 decoder from the shared secret (ADR-040), and webhook and engagement each carry a
byte-for-byte copy of that same decoder (ADR-042/043) — each verifies independently, none trusts a
caller's say-so, defense in depth at every hop. What is *not* yet distributed is token **issuance**:
register/login/refresh/logout still run inside the monolith. This PR moves issuance out. Validation
does not move because it already left, service by service, in every prior extraction.

That makes identity the mirror image of risk (ADR-044). Risk was a synchronous call *into* a new
service that a money-path operation waits on. Identity is a service nothing in-process calls at all —
its callers are external clients reaching `/api/v1/auth/**` and `/api/v1/users/**` through the
gateway. The consequence runs through every decision below: the rollback shape, the absence of a mode
flag, and the absence of a monolith migration all follow from "no in-process caller to switch."

## Decision

### 1. Issuance moves; validation stays shared-HS256 — the plan's "public key" is deferred, not adopted

The plan of record describes validation staying distributed *"via public key / introspection."* That
phrasing implies **asymmetric** signing (RS256/EdDSA): identity holds a private key, everyone else
fetches a public key or a JWKS. The implementation is not that. Every token on the platform is
**HS256 signed with a shared secret** (`JwtAccessTokenService`), and every validator — gateway,
webhook, engagement — reconstructs the same symmetric decoder from the same
`paymesh.security.jwt.secret`. Surfacing this divergence rather than silently resolving it either way
is a CLAUDE.md requirement; this is the resolution.

**PR 10 keeps shared-HS256 and moves only issuance.** The reasons:

- Moving to asymmetric keys is not the identity *extraction*; it is an orthogonal cryptographic
  redesign that touches **every** validator (gateway + webhook + engagement + each future service),
  adds a JWKS endpoint, and needs a key-rotation story. Bundling it into the extraction would be
  exactly the "unrelated redesign smuggled into a move" that ADR-041 warned the pilot against.
- The extraction's goal — issuance runs as its own deployable — is fully met with HS256. The identity
  service mints with the shared secret; the gateway and the other services keep validating as they
  already do. A token minted by the new deployable validates identically everywhere on day one,
  because it is the same algorithm and the same key.
- Asymmetric signing earns its own PR when there is a reason to stop sharing the secret (a service run
  by a party that should verify but never mint). `JwtAccessTokenService`'s javadoc already names that
  day and the one class that changes; nothing here forecloses it. ADR-051 (decommission) or a
  dedicated key PR is the natural home.

So identity's `JwtAccessTokenService` moves verbatim — it still both mints and verifies, and
`IdentityConfiguration` still exposes its decoder as the `JwtDecoder` bean the filter chain uses, so
the issuer and its own verifier never drift. The divergence between the plan's target shape and the
shipped shape is recorded here, not papered over.

### 2. Dual path with no mode flag, and therefore no monolith migration

ADR-044 made rollback a property flip (`paymesh.risk.mode`) because the monolith was the **caller** and
could choose which implementation to invoke. Identity has no in-process caller to choose for — the
switch point is the **gateway route**, not a bean inside the monolith. So "dual path" here takes the
PR 6 (simulator) shape, not the PR 9 (risk) shape: the monolith keeps the whole `com.paymesh.identity.*`
subtree compiling, wired, and reachable against the `identity` schema, and rollback is **re-pointing
the gateway's `identity-uri` back at `backend-uri`** — a monolith still carrying the old code. There
is deliberately **no `paymesh.identity.mode`**: a flag nothing reads is dead configuration, and the
honest switch is the one route value.

Because the monolith keeps everything, there is **no monolith migration and no monolith code change**
this PR. Nothing is dropped: the identity code stays, its `identity`-schema access stays (the monolith
still runs as the all-schema role ADR-038 left it), no `ModuleBoundaryTest` allowance changes (nothing
in the monolith imports `com.paymesh.identity.*` across a boundary to begin with — callers depend on
`shared.security.AuthenticatedCaller`, not on identity), and no `merchant_ref` fan-out shrinks
(identity consumes no `merchant_ref`). The entire PR footprint outside the new `identity/` module is in
`gateway/`: one route bean, one URI property, one route test. Fencing the monolith's identity code and
schema access is a later PR's work, once the new deployable is proven — the same deferral ADR-044 made.

### 3. ApiKey→JWT minting stays monolith-fronted

ADR-022's machine auth mints an in-memory JWT from a merchant `ApiKey`, which requires reading
`api_credentials` — a table in the **`merchant`** schema, out of `identity_svc`'s reach until Merchant
extracts (PR 11). This is the exact constraint ADR-042 flagged for webhook. The identity deployable is
therefore **JWT-only at its own boundary** (no `ApiKeyAuthenticationFilter`), like webhook and
engagement; a raw `ApiKey` is still handled where `api_credentials` is readable — the monolith — and
the gateway fronts it. Moving ApiKey auth is out of scope until the schema it depends on is reachable.

### 4. The schema it already owns, adopted; the platform tables, created

`identity_svc` and the `identity` schema already exist — V38 moved `users`, `user_roles`,
`refresh_tokens` and `security_events` into it and minted the fenced `NOLOGIN` role for this day. The
module's `V1` **adopts** those four tables (`baseline-on-migrate`), authored byte-for-byte as the live
post-V40 shape so a fresh database (Testcontainers) and the shared dev database converge — every
constraint kept, including ADR-027's partial unique indexes and the `PLATFORM_ADMIN iff merchant_id IS
NULL` biconditional on `user_roles`, and the ADR-026/V26 identifier-format CHECKs. The one difference
from the monolith's originals is the dropped FK to `merchants`: `identity_svc` has no `merchants` table
in reach to reference (ADR-038 fencing), the same adoption-of-reality ADR-042/043 made. `V2`
**creates** identity's own `processed_events`/`idempotency_records`/`outbox_events`, with
`outbox_events.merchant_id` nullable from the start — because identity's own producer needs it to be.

### 5. Comms: synchronous for auth, event-fed for audit (already)

Login, refresh and logout are synchronous request/response — a caller needs its token now, not
eventually. Role-change **audit** is event-fed and already was: ADR-043 wired identity's `OutboxWriter`
and had `ManageUserAccessService` append `identity.user_access.audited` to the outbox in the same
transaction as the privileged action, for engagement to turn back into an `audit_events` row. That
producer moves with identity unchanged; this is precisely why ADR-043 made `OutboxEvent.merchantId`
nullable (five of the seven user-access actions are platform-scoped, with no merchant to name) — a
decision taken then specifically so it would not break again at identity's own extraction. It did not.

### 6. Gateway: auth public at the edge, user-admin JWT-gated

`identityRoutes` (`@Order(0)`) forwards `/api/v1/auth/**` and `/api/v1/users/**` to
`paymesh.gateway.identity-uri` (`:8086`), rate-limited as client traffic. `@Order(0)` is load-bearing
for the same reason it is on `webhookRoutes`/`engagementRoutes`: both paths are subsets of
`apiRoutes`' `/api/**`, so without an explicit order a login could silently fall through to the
monolith. The edge already permits `/api/v1/auth/**` unauthenticated (ADR-040) — it must, or login
would 401 before it could ever mint a token — while `/api/v1/users/**` falls under
`anyRequest().authenticated()` and stays JWT-gated at the edge *and* re-validated at the deployable.
`GatewayIdentityRouteTest` proves the precedence and the unauthenticated pass-through.

## Consequences

- The platform stays on a **shared symmetric secret**. Every validator holds a key that can also
  *mint* — the accepted cost of HS256, unchanged by this PR and now explicitly deferred to a future
  asymmetric-keys PR rather than left as an implied target. The divergence from the plan's "public
  key" wording is documented, not silent.
- Rollback is a **gateway URI re-point**, not a property flip — the PR 6 shape, because identity has no
  in-process caller. The monolith keeps identity's code and schema access until a later fencing PR; the
  `com.paymesh.identity.*` duplication between `backend` and `identity/` is the price of that rollback.
- The `shared` subtree is duplicated into a seventh module until PR 16 makes it a library — the
  established, stated cost; drift is caught only by each side's boundary test.
- ApiKey→JWT minting remains monolith-fronted until PR 11 puts `api_credentials` within reach. Until
  then a raw `ApiKey` presented directly to the identity deployable is out of scope by construction.
- **The global merchant-status gate does not follow identity.** The monolith's `MerchantStatusFilter`
  refuses writes from a non-`ACTIVE` merchant across `/api/v1/*`, including the merchant-access routes
  in `UserAdminController`, though identity never imports `MerchantStatusGate`. Identity takes
  engagement's side of that line (ADR-043): no merchant-status gate, no `merchant_ref`. A
  merchant-access grant against a suspended merchant is therefore accepted here where the monolith
  refused it. Accepted and stated; the same narrowing ADR-043 recorded for report exports.
- **Scheduling is declared by the deployable, not inherited.** The outbox relay is `@Scheduled`, and
  unlike risk and webhook identity has no monolith configuration to enable it. `IdentityConfiguration`
  carries `@EnableScheduling`, and `IdentityConfigurationTest` fails if it is removed. Without it,
  `identity.user_access.audited` events would be written and never published, silently.
- **Inherited, not fixed here.** The extraction copied these from the monolith verbatim, and PR 10 is a
  move, not a redesign. Each is recorded so it is not mistaken for something this PR introduced:
  - `POST /api/v1/auth/register` (public) grants `MERCHANT_ADMIN` at whatever `merchantId` the caller
    supplies, with no check that the merchant exists or that the caller has any relation to it. This is
    the most serious of them and needs its own decision (the onboarding flow it supports, and what
    should gate it) before any fix.
  - `ManageUserAccessService` role-change writers read the user without `requireForUpdate`, so two
    concurrent changes to one user can lose an update. `revokePlatformAdmin` already takes the lock.
  - The bootstrap platform-admin runner does not check the target's status, and it never re-runs.
  - The unknown-email timing equalizer is a fixed cost-10 hash, not the configured strength.

  These are the next identity hardening PR's scope, ahead of PR 11.

## Addendum: the inherited register gap is closed

The first inherited issue listed above, public `POST /api/v1/auth/register` granting `MERCHANT_ADMIN`
at any caller-supplied `merchantId`, is resolved. The field is dropped from the register request and
command; registration always creates a user with no roles. Merchant access is granted afterwards by an
admin through `POST /api/v1/users/{userId}/merchant-access`. A regression test fails if the self-grant
returns. Rejected: keeping the field behind a platform approval step, which adds an approval state for
a route that never needed to grant anything at registration. The identity dev profile's outbox relay is
also now on, the same fix merchant shipped with.
