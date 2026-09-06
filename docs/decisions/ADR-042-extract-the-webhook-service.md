# ADR-042: Extract the webhook service — the first merchant-facing leaf

- Status: Accepted
- Date: 2026-09-06
- Scope: PayMesh Phase 3B, PR 7. New `webhook/` module (port 8083). One new migration each in the
  monolith and in the new module's own Flyway history. Follows ADR-038 (schema-per-service),
  ADR-039 (merchant_ref projection), ADR-040 (the gateway), ADR-041 (the extraction recipe), and
  builds on ADR-028 (webhook), ADR-035 (audit), ADR-037 (dual-path relay). Plan of record:
  `docs/phase-3-microservices-extraction-plan.md` §3B PR 7.

## Context

ADR-041 proved the extraction recipe on the provider simulator — the easy case, chosen precisely
because ADR-017 had built it with **zero** references to PayMesh in either direction: no PayMesh
table, no other capability imported, no PayMesh token on its surface. It copied exactly one shared
class and moved.

Webhook is the first *merchant-facing* leaf, and it is the first extraction where the recipe meets
everything the pilot deliberately did not have to prove:

- it is **Kafka-fed** from real domain events (`payment.succeeded`/`payment.failed`/
  `refund.succeeded`/`order.paid`), so it is the first service to actually cash in the dual-path
  relay's promise (ADR-037) rather than merely being compatible with it;
- it serves an **authenticated merchant API** (`/api/v1/webhook-endpoints/**`), so it needs a JWT
  boundary and the merchant-status gate (ADR-021/ADR-039), not a single shared key;
- it records a **privileged action** — secret rotation — through `AuditRecorder` (ADR-035), and the
  `audit_events` table left this service's reach when ADR-038 put it in the `engagement` schema.

The webhook capability's own code is already isolated the way the pilot's was: it imports only
`com.paymesh.webhook` and `com.paymesh.shared`, no other capability. What it leans on is the
*platform* — security, tenancy, the outbox/inbox, idempotency. That platform is what this PR has to
carry across a process boundary for the first time.

## Decision

### 1. The capability moves verbatim; the shared platform is copied, not shared

`com.paymesh.webhook` relocates from `backend/src/main/java` to `webhook/src/main/java`, unchanged —
same classes, same package, same tests where they survive (§7). The only genuinely new webhook-owned
files are what a standalone Spring Boot app needs: a `WebhookApplication` main class, an
`application.yaml`/`application-dev.yaml`, a `pom.xml`, a `SharedBeansConfiguration` (`Clock` +
`TransactionTemplate`, exactly as `provider-sim` split them out).

The pilot copied one shared class (`ApiErrorResponse`) and *renamed* it into its own package. Webhook
depends on ~30 shared classes across `shared.{api,audit,outbox,security,tenant,idempotency}`.
Renaming all of them would rewrite every import in the moved capability for no benefit. So this PR
copies the **needed `com.paymesh.shared.*` subtree verbatim, package names unchanged** — webhook's
own imports do not move a character. This is the same "copy, do not share" call ADR-041 made, scaled
from one class to a subtree: `shared/` does not become a library until PR 16, and pulling that
forward for one extraction is the speculative-infrastructure move this project keeps refusing. The
duplication is the stated, paid cost; a `ModuleBoundaryTest`-style guard in each module keeps the two
copies from drifting into each other's concerns.

The copied subtree is the transitive closure actually reached at runtime, and no more:
`shared.api.ApiErrorResponse`; `shared.tenant.*` (the `merchant_ref` gate, store, projector);
`shared.outbox.*` (the consumer half — `EventDispatcher`, `EventHandler`, `KafkaEventListener`,
`EventEnvelope`, `processed_events` inbox — **and** the producer half — `OutboxWriter`, the relay,
`KafkaEventPublisher` — see §4); `shared.idempotency.*` (the `/replay` route is on
`IdempotentRoutes`); and the JWT half of `shared.security.*` (§3). The audit *writer*
(`AuditRecorder` and its JPA store) is **not** copied — webhook no longer writes `audit_events` (§4).

### 2. One cluster, the `webhook` schema, the role ADR-038 already minted; the inbox and idempotency
tables it did not

`webhook` connects to the same PostgreSQL cluster as `webhook_svc`, `search_path` narrowed to
`webhook, public`, exactly the shape ADR-041 established for `simulator_svc`. The three `webhook_*`
tables and the `merchant_ref` read model already live in the `webhook` schema (ADR-038 V38,
ADR-039 V39). Flyway gets its own history in the `webhook` schema, `baseline-on-migrate: true` /
`baseline-version: 1`, adopting those existing tables rather than re-creating them — the ADR-041
pattern unchanged.

What ADR-038 left in `platform` and this service now needs its **own** copy of, because
`webhook_svc` cannot read another schema's tables:

- `processed_events` — the inbox. A Kafka consumer without an inbox is not idempotent, and the
  platform copy is unreachable. ADR-038 flagged exactly this: "split per-service at 3B+, not now."
  This is that split, for webhook only.
- `idempotency_records` — the `/deliveries/{id}/replay` route runs through `IdempotencyFilter`.
- `outbox_events` — webhook now produces one event type (§4), so it needs a durable outbox.

These three are created by the module's `V2` migration in the `webhook` schema (`V1` adopts the
pre-existing tables per §above). They are new physical tables in an already-carved schema, not moved
tables, so no monolith migration touches them.

### 3. Security is JWT-only at this boundary, mirroring the gateway — the ApiKey filter does not come

The monolith's `SecurityConfiguration` runs `ApiKeyAuthenticationFilter` *inside* the chain
(ADR-022), because minting a JWT from an `ApiKey` requires reading `api_credentials`. That table is
in the `merchant` schema; `webhook_svc` cannot read it, and will not until Merchant is extracted
(PR 11). So webhook carries the **JWT half only**: `oauth2ResourceServer().jwt()` validating the
same shared HS256 secret the gateway validates (ADR-040), the `AuthenticatedCaller` argument
resolver, and the JSON `AuthenticationEntryPoint`/`AccessDeniedHandler` so a rejection has the same
`{code,message}` shape as everywhere else. This is precisely the edge's own posture: the gateway
validates JWTs and never touches `api_credentials`; `ApiKey → JWT` exchange stays a monolith-internal
concern. A machine caller presenting a raw `ApiKey` directly to webhook is therefore out of scope for
this PR — the documented topology puts the gateway in front, and ApiKey exchange returns to the
picture when Identity/Merchant are extracted (PRs 10–11). Stated, not hidden.

The merchant-status gate (ADR-021) *does* come, unchanged: register and rotate are merchant writes,
the gate reads the `merchant_ref` projection (ADR-039), and the projection is fed by a
`MerchantRefProjector` consuming `merchant.*` over the same Kafka consumer as the domain events (§5).
An absent projection row is the ADR-039 third outcome — 503 `MERCHANT_NOT_YET_AVAILABLE`, retryable —
not a 403.

### 4. Auditing a secret rotation moves from an in-process call to an outbox event — PR 8's mechanism,
one action early

`RotateWebhookSecretService` calls `AuditRecorder.record(...)` inside the rotation transaction, so
the audit row and the version bump commit together (ADR-035's whole point: "a failure to record is a
failure to act"). Across a process boundary that call cannot survive — `webhook_svc` cannot write
`audit_events`, now in the `engagement` schema.

Three options were weighed. **(B)** dropping the audit until PR 8 wires the event is a real,
temporary regression of a security-log invariant this project treats as non-negotiable, on a
security-sensitive action — rejected. **(C)** a synchronous audit API call is rejected by ADR-035
itself: the guarantee cannot hold across a network call that can fail after the action commits.
**(A)**, chosen: webhook emits a `webhook.secret_rotated.audited` event to **its own outbox, in the
same transaction as the rotation**, and the monolith's audit capability consumes it and writes
`audit_events`. This is exactly the mechanism PR 8 (ADR-043) defines for the whole Audit extraction,
pulled forward for webhook's single audited action — not throwaway, the shape PR 8 generalizes. It
moves the atomicity from "same DB transaction as the action" to "same *outbox* transaction as the
action," which is strictly stronger than the synchronous call it replaces.

The monolith side is deliberately small: its `KafkaEventListener` already consumes every `.+-events`
topic and dispatches to every `EventHandler` (ADR-037). This PR adds **one** handler —
`RecordWebhookSecretRotationAuditHandler`, subscribed to `webhook.secret_rotated.audited`, calling
the existing `AuditRecorder` inside its inbox transaction — plus the topic webhook publishes to. No
new audit table, no audit API, no change to `AuditRecorder`. The hashed before/after and the actor id
travel in the event payload, so the audit row is byte-for-byte what the in-process call wrote.

The webhook producer side is the outbox it now carries (§2): `OutboxWriter.append` in the rotation
transaction, the relay timer, `KafkaEventPublisher`. `ponytail:` webhook runs the full relay for one
event type today; that is the established pattern copied, not invented, and the alternative (a bespoke
one-off publish) would be a second, worse copy of the outbox contract.

### 5. One Kafka consumer, its own group; the pattern-subscription needs no per-event wiring

Webhook gets one `@KafkaListener` on the `.+-events` topic pattern (ADR-036/ADR-037), its own
consumer group `paymesh-webhook` (each extracted service gets its own group — ADR-037 §the monolith's
is the transitional single reader). That one listener feeds `EventDispatcher`, which fans out to
every registered `EventHandler` and dedupes through the webhook inbox: the four `WebhookFanOutHandler`
beans and the `MerchantRefProjector`, exactly as they were wired in-process. Subscribing to a fifth
event stays one bean in `WebhookConfiguration`. The monolith keeps publishing these events on its own
relay; webhook is now a genuinely independent second consumer, which is the dual-path promise made
literal.

### 6. The gateway re-points one prefix; nothing else in it changes

`RoutesConfiguration` gains a `webhookRoutes` for `/api/v1/webhook-endpoints/**` pointed at a new
`webhook-uri` property (default `http://localhost:8083`), the same one-prefix re-point ADR-041 made
for `/sim/v1/**`. The edge's public/authenticated split is untouched — webhook's routes were already
`authenticated()` and stay so; the gateway validates the JWT before forwarding, and webhook validates
it again (defense in depth, ADR-040).

### 7. Tests: move what survives, adapt the cross-boundary ones with the WireMock substitution

Domain and application unit tests move unchanged. `WebhookEndpointPersistenceTest` and the
merchant-facing `WebhookIntegrationTest` move to the module against a real PostgreSQL (Testcontainers,
`webhook` schema, its own Flyway). The rotation-audit test can no longer assert an `audit_events`
row in-process — it asserts instead that a `webhook.secret_rotated.audited` row lands in the webhook
outbox with the right hashed before/after (the boundary the extracted service is now responsible
for), the same "assert what *this* module owns" substitution ADR-041 made. A new monolith test proves
the consumer half: a `webhook.secret_rotated.audited` envelope through the dispatcher writes the
`audit_events` row. `ModuleBoundaryTest`'s webhook allowances (the `AuditRecorder` dependency it
called out) are updated — webhook no longer imports `shared.audit` at all.

## Consequences

- Webhook is a genuinely separate deployable: own pom, own port (8083), own Flyway history, own
  consumer group, own secret (`paymesh.webhook.master-key`) guarded by its own startup check. Four
  independently-built Maven modules now exist (`backend/`, `gateway/`, `provider-sim/`, `webhook/`).
- The invariant is now literally cross-process: a merchant endpoint being down, or webhook itself
  being down, cannot touch a payment — the payment commits in the monolith and its event waits in
  Kafka for webhook to return. This is the verification target the plan names.
- Rollback is the gateway re-point (ADR-041's shape): point `webhook-uri` back at a monolith still
  carrying the code, plus the dual-path relay meaning no event is lost while webhook is absent. The
  monolith's `webhook.secret_rotated.audited` consumer is harmless whether or not webhook is running.
- **Accepted, stated costs:** (1) the `shared` subtree is duplicated between `backend` and `webhook`
  until PR 16 makes it a library — drift is possible and only a boundary test catches it; (2) a raw
  `ApiKey` presented directly to webhook is unsupported this PR (the gateway fronts it; exchange
  returns with PR 10–11); (3) webhook runs the full outbox relay for a single event type, the
  established pattern's fixed cost paid for one action's correctness.
- Postman gains a third base variable, `{{webhookBaseUrl}}` (`:8083`), for the Webhook folder — the
  pattern ADR-041 started with `{{simBaseUrl}}`.
