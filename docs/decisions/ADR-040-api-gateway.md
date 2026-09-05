# ADR-040: The API gateway — one north-south front door, auth and rate limit at the edge

- Status: Accepted
- Date: 2026-09-05
- Scope: PayMesh Phase 3A, PR 5. New `gateway/` module. No migration. Follows
  ADR-037 (depends on the relay/backbone work, not on ADR-038/039). Plan of
  record: `docs/phase-3-microservices-extraction-plan.md` §3A PR 5.

## Context

The plan's north-south rule (§"Synchronous where a caller needs an answer now"):
client → platform traffic goes through an API gateway that authenticates, routes
and rate-limits; east-west (service → service) stays direct. Standing the gateway
up now, while everything is still one process, means that when services split in
3B the front door and its contracts already exist — a route re-points from the
monolith to a service, and no client changes.

Nothing is extracted here and no code leaves the monolith. The gateway is a second
deployable in front of the one that already exists; a client may still address the
monolith directly on 8080 until 3B, so the gateway is optional until then.

## Decision

### 1. A standalone `gateway/` module, not a reactor conversion

The gateway is its own Maven module beside `backend/`, with its own `pom.xml`, its
own wrapper, and its own port (8081). The repo is **not** converted to a
multi-module reactor now. PR 5's job is one front door; a parent aggregator pom
earns its keep once 3B has several service modules to aggregate, and doing it now
would restructure every existing build and CI path for a single new module. The
two builds stay independent (`cd gateway && ./mvnw test`).

### 2. Spring Cloud Gateway Server **WebMVC**, on Boot 4.1 with the check waived

The gateway is Spring Cloud Gateway Server WebMVC 5.0.0 (Spring Cloud `2025.1.0`).
The **servlet** variant, not the reactive one: this platform is webmvc, and the
reactive gateway would drag a second runtime model into the org for no gain.

Spring Cloud `2025.1.0` pins its compatibility verifier to Boot **4.0.x**; the
platform runs Boot **4.1.0**, the next minor. The gateway server, the router DSL
and the rate-limit filter all work on 4.1 — only the verifier's exact-match table
does not list it yet. We set `spring.cloud.compatibility-verifier.enabled=false`
rather than hold the whole platform back a minor. Deleted the day a Spring Cloud
train lists Boot 4.1.

### 3. Edge authentication mirrors the monolith's public/authenticated split exactly

The gateway is a Spring Security resource server that rebuilds the **same HS256
decoder** the monolith verifies with, from the **same shared secret**
(`paymesh.security.jwt.secret`). It is not a second authority; it is the same
check moved to the front. A missing or invalid token is refused **at the edge**
with the monolith's own `401 {code:"UNAUTHENTICATED", message}` body, so a client
sees one 401 shape wherever it lands.

The permit list is a copy of `shared.security.SecurityConfiguration`'s, and it has
to be, because the monolith does **not** require a bearer token everywhere:

| Route | Edge behavior | Why |
|---|---|---|
| `/api/v1/**` (most) | **JWT required at edge** | ordinary authenticated API |
| `/api/v1/auth/**` | pass through | getting a token cannot require one |
| `POST /api/v1/merchants` | pass through | self-service signup precedes any credential |
| `/internal/v1/**` | pass through | provider/refund/payout callbacks authenticate by **HMAC** at the monolith |
| `/sim/v1/**` | pass through | the simulator authenticates by a **shared key** at the monolith |
| `/actuator/health`,`/info` | pass through | the gateway's own liveness |

If the edge demanded a JWT on the callback or simulator routes, every one would
401 here before the monolith's signature/key filter ever ran — stranding a payment
already taken. The gateway validates the token; it does **not** authorize the
merchant. Which merchant a caller may act for stays a per-row decision the monolith
makes next to the data (ADR-024, `AuthenticatedCaller`); the edge proves the token
is real and unexpired, nothing more. **The shared secret is the cost of symmetric
(HS256) tokens.** When identity is extracted (3C) and moves to asymmetric keys, the
decoder bean fetches a public key / JWKS and the shared secret disappears — one
line.

### 4. The route table points everything at the monolith today

Two route groups, split by whether the rate limit applies:

- **`/api/**`** is rate limited (client traffic; the one unauthenticated write on
  it, `POST /api/v1/merchants`, is the abuse vector the monolith always flagged).
- **`/internal/**` and `/sim/**`** are forwarded **without** a limit: a provider
  retrying a delivery it is contractually required to retry must not be throttled
  into a failure that strands money already moved.

Every route forwards to `paymesh.gateway.backend-uri` (the one monolith). In 3B
each prefix re-points at its own service and nothing else in the class changes —
that re-pointing being a one-line edit here, not a client change, is the whole
reason the gateway exists before the services do.

**The proxy hop is pinned to HTTP/1.1.** The webmvc gateway's JDK forwarding
client otherwise offers HTTP/2, and a plaintext (h2c) POST upgrade to a backend
that does not speak it cleanly gets its request body cancelled mid-stream
(`RST_STREAM`). The monolith is a plaintext HTTP/1.1 origin, so HTTP/1.1 is what
this hop actually is; pinning it makes that explicit. Revisit if a backend is ever
fronted by TLS h2.

### 5. Rate limiting is Redis-backed and distributed, via bucket4j

The webmvc gateway's `rateLimit()` filter pulls an `AsyncProxyManager` bean from
the context and builds a distributed bucket against it. We back that with **Redis**
(bucket4j's Lettuce module), keyed by client IP, so N gateway instances share one
limit instead of each admitting the full quota — the reason to choose Redis over a
per-process in-memory limiter even while there is only one instance. A new
`redis` service joins `docker-compose.yml`; only the gateway talks to it, so there
is no Spring Data Redis, just the raw client bucket4j needs.

Two pins this required, both contained to the gateway module and documented in the
pom:
- **Lettuce 6.3.2**, not the 7.5.x Boot 4.1 manages — bucket4j 8.15's CAS path was
  built against Lettuce 6, and 7 is binary-incompatible on that path. Lifted when
  bucket4j ships a Lettuce-7 build.
- The proxy manager is **String-keyed** (`RedisCodec.of(StringCodec, ByteArray)`),
  because the filter passes the resolved key (an IP String) straight through;
  `builderFor(RedisClient)` would build a `byte[]`-keyed manager and the String
  key would fail to encode.

**Fails open, and returns the house error shape.** Redis is a throwaway counter
store, not an authority (the graceful-degradation rule: it may fail without
corrupting payments). If it is unreachable the filter throws *before* it forwards,
and `RoutesConfiguration#rateLimitGuard` catches that — narrowed to a Lettuce
`RedisException` in the cause chain, so a *backend* failure is never mistaken for a
limiter failure and a non-idempotent write is never re-forwarded — and forwards
the request unlimited rather than 500-ing all `/api`. The Lettuce client is set to
`REJECT_COMMANDS` on disconnect with a 2s timeout, so the outage fails *fast* into
that open path instead of hanging gateway threads. The same guard rewrites
bucket4j's bare `429` into the `{code:"RATE_LIMITED", message}` body, so a throttled
client parses the same shape as a `401`.

**Accepted ceiling.** The key is `getRemoteAddr()` — correct while the gateway is
the edge, and deliberately so: trusting a client-supplied `X-Forwarded-For` here
would let anyone spoof their IP and dodge the limit. Put a *trusted* load balancer
in front and the switch is one property, `server.forward-headers-strategy=framework`;
do not set it while the gateway is directly reachable. Flagged in code; not built,
because there is no LB yet.

### 6. The edge decoder matches the monolith's exactly

The gateway's `JwtDecoder` mirrors `JwtAccessTokenService`'s validators — zero clock
skew (`JwtTimestampValidator(Duration.ZERO)`) and `setAllowEmptyExpiryClaim(false)`
— not Nimbus's laxer defaults (60s skew, empty `exp` allowed). Otherwise "the same
check moved to the front" would not be true: a token up to a minute stale, or one
missing `exp`, would pass the edge and be rejected only by the monolith. And the
`/internal/**` permit is the **three exact callback paths** the monolith permits
(`provider-`, `refund-`, `payout-callbacks`), not a blanket prefix, so the edge
mirrors the boundary rather than forwarding traffic the monolith default-denies.

## Consequences

- One north-south entry point exists, doing auth, routing and rate limiting, with
  the route table already shaped for per-service re-pointing in 3B. No client
  change when a service splits out — only a route's target URI.
- The gateway needs the shared JWT secret and a running Redis to start. In dev,
  `docker compose up -d redis` and the `dev` profile (which supplies the throwaway
  secret matching the monolith's) are enough; the monolith need not be up for the
  gateway to boot (routes resolve lazily).
- Edge validation duplicates the monolith's authentication rather than replacing
  it — defense in depth, and deliberately so: the monolith still authenticates
  every request itself, because a service must never trust that something in front
  of it did. The gateway is a filter, not the only lock.
- Verification is the two edge behaviors the plan names, tested against a real
  Redis (Testcontainers) and a WireMock backend: an unauthenticated call to a
  protected route is refused **at the gateway** and never reaches the backend; an
  authenticated one is forwarded unchanged; the HMAC/shared-key routes pass through
  without a bearer token; and a burst past capacity gets `429` from the edge. Full
  end-to-end pass-through of every Postman request stays the Postman/newman job
  (the Java suites cannot see cross-service HTTP-surface regressions).
- The gateway is optional until 3B: rollback is "clients address the monolith on
  8080 directly," and nothing in the monolith depends on the gateway existing.
