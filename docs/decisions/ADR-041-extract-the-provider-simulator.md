# ADR-041: Extract the provider simulator — the pilot

- Status: Accepted
- Date: 2026-09-06
- Scope: PayMesh Phase 3B, PR 6. New `provider-sim/` module. No new migration in the shared
  database (the `simulator` schema and its five tables already exist — ADR-038). Follows
  ADR-038 (schema-per-service) and ADR-040 (the gateway). Plan of record:
  `docs/phase-3-microservices-extraction-plan.md` §3B PR 6.

## Context

3A built the scaffolding — Kafka, the dual-path relay, ten schemas with nine fenced roles, a
gateway — while the monolith stayed one process. This is the first PR that actually moves
code out of it.

ADR-017 built the provider simulator specifically so this day would be cheap: it holds **zero**
references to PayMesh in either direction. `ModuleBoundaryTest` enforced an empty
cross-capability allowlist *both ways* from the day it was written — stricter than any other
pair in that file. It writes no PayMesh table. Its inbound surface (`/sim/v1/**`) authenticates
with its own shared key, not a PayMesh token. Its outbound surface is an HTTP POST of an
HMAC-signed body at a callback route, exactly what a third party's would be. If it were deleted,
every test outside its own package would still pass. The plan names it the pilot for exactly
this reason: prove the extraction recipe where nothing financial is at risk while doing so,
before repeating it on a service that shares real event traffic.

## Decision

### 1. The move is literal: the package does not change, only its process does

`com.paymesh.simulator` relocates from `backend/src/main/java` to
`provider-sim/src/main/java`, unchanged — same classes, same package name, same tests (moved,
not rewritten, where they could be). The only new files are the ones a standalone Spring Boot
app needs and the monolith already had one of: a main class, an `application.yaml` /
`application-dev.yaml`, a `pom.xml`. This is possible *only* because ADR-017's isolation was
real; a module with even one shared type would have made this a rewrite.

`com.paymesh.shared.api.ApiErrorResponse` is the one import the move could not carry over as-is
— it is the module's only dependency on anything outside itself. `provider-sim` gets its own
copy, byte-for-byte, at `com.paymesh.simulator.api.ApiErrorResponse` — the same call the gateway
already made for its own `ApiErrorResponse` (ADR-040). `SimulatorApiKeyFilter` needs no
Spring Security dependency at all: it is a plain `OncePerRequestFilter` from `spring-web`, so
`provider-sim`'s pom carries no security starter — smaller than the monolith's, not just a
subset of it.

### 2. One physical cluster, one schema, the role ADR-038 already built for this exact day

`provider-sim` connects to the **same PostgreSQL cluster** the monolith uses, `search_path`
narrowed to `simulator, public` — not the monolith's ten-schema list, because this deployable
maps no entity outside its own schema. It connects as `simulator_svc`, the fenced `NOLOGIN`
role ADR-038 minted with exactly this moment in mind ("be the connection role each service
adopts verbatim when it is extracted"). Two manual grants make that literal: `ALTER ROLE
simulator_svc LOGIN PASSWORD '...'` and `GRANT CREATE ON SCHEMA simulator TO simulator_svc`
(the second because Flyway's baseline needs to create its own history table, and ADR-038 only
granted USAGE on the schema, not CREATE).

**Flyway gets its own history, in its own schema, adopting rather than re-creating.** The five
`simulator` tables already exist — the monolith's V13, moved into that schema by V38.
`provider-sim`'s `db/migration/V1__create_provider_simulator.sql` is byte-for-byte that same
DDL, kept so a genuinely fresh database (a new environment, a Testcontainers run) bootstraps
identically — but against the shared dev database, `baseline-on-migrate: true` with
`baseline-version: 1` makes Flyway **adopt** the existing tables at V1 rather than re-run
`CREATE TABLE` against a schema that already has them. This is the "deliberate one-time
adoption of a pre-existing schema" application.yaml's own comment already named as the reason
`baseline-on-migrate` exists.

### 3. Nothing about the wire protocol changes — HTTP signed callbacks stay HTTP signed callbacks

The Phase 3 plan's own prose for this PR says outbound callbacks would "publish to Kafka." They
do not, and that is a stated correction rather than a silent rewrite. ADR-017 built the
simulator's outbound side as a scheduled dispatcher POSTing an HMAC-signed body directly at
`/internal/v1/provider-callbacks/{provider}` — already a real, configurable network call, never
a Kafka topic, because the simulator predates the Kafka backbone (ADR-036) by nineteen ADRs and
was never wired to it. Moving the simulator to Kafka would be a **second, unrelated redesign**
bundled into an extraction PR — a real behavior change to the money path's callback ordering
and dedup contract (ADR-012), attempted at the exact moment the plan says to change the fewest
things possible. The pilot's job is to prove the recipe, not to also relitigate the transport.
`provider-sim`'s `paymesh.simulator.callback-url` / `payout-callback-url` point at the monolith
exactly as before, just across a real process boundary instead of a loopback one — the
`SimulatorProperties` javadoc already argued this was a URL for exactly this day, not a method
call.

The reverse direction was already a real network call too. `ReconciliationConfiguration`
(`paymesh.reconciliation.base-url`) and `SubmitPayoutsService`
(`paymesh.settlement.payouts.url`) already addressed the simulator over HTTP — the "loopback"
comments in both said so, in anticipation of this exact PR. Extraction changes one number in
each: `8080` becomes `8082`.

### 4. The gateway re-points one prefix; nothing else in it changes

`RoutesConfiguration#passthroughRoutes` split into `internalCallbackRoutes` (still
`backend-uri`) and a new `providerSimRoutes` (`provider-sim-uri`, new property, defaulting to
`http://localhost:8082`). `SecurityConfiguration`'s edge permit list is untouched — `/sim/v1/**`
was already unauthenticated at the edge because the receiver has no bearer token to evaluate;
that reasoning does not care which deployable answers.

### 5. The two tests that could not survive verbatim, and what replaced them

Two backend tests imported `com.paymesh.simulator` directly to drive a real cross-boundary
scenario in one JVM. Neither claim survives extraction as a single JUnit test, because the two
sides are now two processes with two classpaths — this is the plan's own stated risk
("the Java suites cannot see cross-service HTTP-surface regressions"), arriving one PR early.

- **`SimulatorCallbackDeliveryIntegrationTest`** moved to `provider-sim`, with the monolith's
  receiver replaced by a WireMock stub — the same substitution the gateway's own tests already
  make for the monolith (ADR-040). What it proves is unchanged: real HTTP, real HMAC signing
  (independently recomputed in the test, not borrowed from the sender under test), and the four
  ADR-012 ordering/dedup scenarios (duplicate, out-of-order, 404-retry, one-bad-row-does-not-
  block-the-batch) against real responses. What moved to the *other* side of the boundary — "a
  signed callback actually flips a payment intent to SUCCEEDED" — is `ProviderCallbackApiTest`
  and `ProviderCallbackIntegrationTest`, which already hand-sign a callback body independently
  of the simulator (the pattern ADR-019 established for Refund callbacks).
- **`ReconciliationIntegrationTest`** stayed in the monolith (`ReconcileProviderDayService` is
  its capability, not the simulator's) with the simulator's `/sim/v1/reconciliation/{date}`
  response replaced by a hand-built JSON document served by a WireMock stub, in the exact shape
  `HttpProviderReconciliationSource` parses — the same "hand-crafted external representation"
  pattern the reconciliation port's own javadoc already argued for (a wire contract "restated
  rather than shared"). The production `ReconcileProviderDayService` and `PaymentModuleRepair`
  are exercised against a real PostgreSQL, unchanged.

`ModuleBoundaryTest.theSimulatorImportsNoOtherCapability` and `.noCapabilityImportsTheSimulator`
are deleted rather than left to pass vacuously — the directory they scanned no longer exists in
this module. The claim they encoded ("if the simulator were deleted, every other test would
still pass") is exactly what this PR cashes in.

## Consequences

- Provider-sim is a genuinely separate deployable: its own pom, its own port (8082), its own
  schema, its own Flyway history, its own `DevelopmentSecretGuard` (one secret,
  `paymesh.simulator.api-key`, not the monolith's five-now-four). The monolith's own guard
  javadoc and `GUARDED` list shrink to match — a secret guarded twice, once on each side of a
  door, is more confusing than one guard per door.
- Rollback is the dual-path relay's promise cashed out at the routing layer: re-point the
  gateway's `provider-sim-uri` (or a client) back at a monolith carrying the old code, which is
  not deleted from git history and could be restored to `backend/` if this PR needed reverting
  before decommission. In practice, restoring it means re-adding the package, the config block,
  and the `/sim/v1/**` security matcher this PR removed — a revert, not a flag.
- Postman's `{{baseUrl}}` still addresses the monolith directly (`:8080`) for every folder except
  **Provider Simulator**, which now uses a second variable, `{{simBaseUrl}}` (`:8082`) — the
  first folder in this collection to need one, because it is the first prefix actually served by
  a different port.
- The recipe is proven cheap: no code rewrite, one schema already carved, one role already
  minted, two tests adapted rather than deleted, one gateway route re-pointed. The next PR
  (webhook, ADR-042) is the first one where this recipe meets a capability that actually
  consumes Kafka traffic, which this PR deliberately did not have to prove.
