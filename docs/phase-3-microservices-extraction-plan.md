# PayMesh Phase 3 — Microservices Extraction Plan

_Written 23 August 2026, after Phase 2 closed green: 1514 tests, 36 migrations
(V1–V36), 35 ADRs, every SDD Phase-1 and Phase-2 capability built. The system is
one deployable — a modular monolith with strict, test-enforced module
boundaries. This plan takes it to services._

This is the plan of record for turning the modular monolith into a set of
independently deployable services around a Kafka event backbone. It says how the
database splits, how services talk, and — PR by PR — what each step delivers and
what its goal is. Read `docs/project-status.md` for where the code actually is;
read this for where it is going.

**This is a target reference and runs ahead of the code, exactly as the SDD
does.** Nothing here is built. The point of writing it now is that ADR-001 chose
"modular-monolith-first" precisely so this document could be written against real
boundaries instead of guesses.

---

## The governing idea

The monolith was built for this day. Three things already in the code are the
extraction machinery, not incidental design:

1. **The transactional outbox + inbox** (ADR-010, ADR-016). Every state change
   already commits an `outbox_events` row in the same transaction, a relay
   already drains it, and consumers already dedupe through `processed_events`.
   Today the relay hands events to an in-process `EventDispatcher`. Extraction is
   almost entirely *"point the relay at Kafka instead, and let the consumer read
   from Kafka instead."* The contract the handlers implement — a `Map<String,
   Object>` payload, idempotent, throw-to-retry — is already broker-shaped.
2. **Consumer-owned lookup ports** (ADR-008). Cross-module reads already go
   through interfaces the *consumer* owns — `CustomerLookup`, `OrderLookup`,
   `HoldingPeriodPolicy`, `AuditRecorder` — not through reaching into another
   module's tables. Each of those ports is a seam: it becomes either a Kafka-fed
   local read model or a synchronous call, and nothing upstream of the port
   knows which.
3. **`ModuleBoundaryTest`**, the fitness function. It already fails the build if
   one capability imports another's internals. A module that passes it today with
   an empty cross-capability allowlist (the Provider Simulator, the Ledger
   inbound) is a module that can leave the process without breaking a compile.

**The governing invariant does not change and is the reason for every rule
below:** a request may fail or be retried, but committed money movement must
never be lost, silently duplicated, or become unauditable. Distribution makes
this *harder to hold*, not less required — two-phase commit is off the table
(ADR-016 already refused exactly-once), so every cross-service interaction has to
be safe under at-least-once delivery and partial failure.

### Three rules that govern the whole phase

- **No distributed transaction, ever.** A service commits *its own* database and
  *its own* outbox row in one local transaction. That is the only atomic unit.
  Everything else is eventual, reconciled, and idempotent. This is ADR-016's
  at-least-once stance, now load-bearing across process boundaries.
- **A service owns its data; no other service reads its tables.** The database
  splits with the code. Cross-service reads are served by events (a local read
  model the owner feeds) or by a synchronous API call to the owner — never by a
  second service opening a connection to the first one's schema. A shared
  database is a monolith wearing a lanyard.
- **The Ledger extracts last and its invariants move *inside* it.** Debits equal
  credits and entries are immutable are enforced today by deferred constraint
  triggers and immutability triggers *in one schema*. A trigger cannot span
  databases. So the Ledger keeps its whole schema, and everything that used to
  post to it in-process now asks it to, through an API and an event it owns.

---

## Target service topology

The SDD's end state is ~15 services. This plan groups the 21 monolith capabilities
into **9 deployables**, chosen so each owns a coherent slice of the money path and
a coherent slice of the schema. Fewer, fatter services first; split further only
when a real reason appears (independent scaling, independent release cadence, team
ownership) — the same YAGNI that kept the monolith one process this long.

| Service | Capabilities it owns | Why grouped | Coupling |
|---|---|---|---|
| **identity** | Identity & Access (users, roles, API creds, refresh tokens) | Authentication is everyone's dependency; it must stand alone and first | Leaf (no domain deps) |
| **merchant** | Merchant + Settlement config | A merchant and its payout configuration are one lifecycle | Low |
| **provider-sim** | Provider Simulator | Already fully isolated — empty allowlist both directions | Leaf (pilot) |
| **payment** | Order, Payment, Customer, Refund | The synchronous money path; these share the tightest transactional coupling and should not have a network hop between order and payment | **Core** |
| **ledger** | Ledger (accounts, entries, transactions, balances) | Financial source of truth; its triggers cannot be distributed | **Core, extracted last** |
| **settlement** | Settlement (batches, items, payouts) | Reads ledger available-balance, drives provider payouts | Core |
| **risk** | Risk (decisions, denylist) | Called synchronously by payment on confirm | Low |
| **webhook** | Webhook (endpoints, deliveries) | Pure event consumer; per-tenant signing | Leaf |
| **engagement** | Notification, Reporting, Audit | Three pure/near-pure consumers of committed events; all read-side, all "must never affect a payment" | Leaf |

`engagement` groups Notification, Reporting and Audit because all three are
consumers that fail without touching the money path, and none is big enough to
earn its own deployable yet. Audit is the near-pure one — its `AuditRecorder`
port is called in-process today — and §"The Audit exception" covers how that
becomes an event.

The extraction **order** is the inverse of the coupling: leaves and the pilot
first, core domain next, the Ledger last.

```
provider-sim  →  webhook, engagement, risk  →  identity, merchant, settlement  →  payment  →  ledger
   (pilot)         (leaf consumers)              (supporting core)                (core)     (last)
```

---

## Database design

### Principle: schema-per-service, one physical cluster first

Each service owns a **separate schema** (`identity`, `payment`, `ledger`, …), and
only that service's role may touch it. Whether those schemas live in one Postgres
cluster or nine is an *operational* decision deferred behind the *logical* one.
Start with one cluster, many schemas, many roles — it gives the isolation
guarantee (a service physically cannot query another's tables, enforced by grants)
without nine backup regimes on day one. Split to separate clusters per service
when a service needs independent scaling or a separate failure domain; the code
does not change when it moves, because it already only speaks to its own schema
through its own role.

Each service gets **its own Flyway history**. `flyway_schema_history` is
per-schema. V1–V36 stop being one linear sequence and become nine independent
sequences. This is the single most mechanical and most error-prone part of the
split, which is why Phase 3A does it *before* any service leaves the process.

### The 46 tables, mapped to owners

Every table in the monolith today, assigned to exactly one service. This mapping
is the contract the schema split executes against.

| Service (schema) | Tables |
|---|---|
| **identity** | `users`, `user_roles`, `api_credentials`, `refresh_tokens`, `security_events` |
| **merchant** | `merchants`, `merchant_status_history`, `settlement_configs`, `kyc_submissions` |
| **payment** | `customers`, `customer_status_history`, `orders`, `order_state_history`, `payment_intents`, `payment_attempts`, `payment_state_history`, `payment_method_tokens`, `refunds`, `refund_state_history`, `denylist_entries`* |
| **ledger** | `ledger_accounts`, `ledger_entries`, `ledger_transactions` |
| **settlement** | `settlement_batches`, `settlement_items`, `payouts`, `payout_attempts`, `payout_callbacks`, `refund_callbacks` |
| **risk** | `risk_assessments`, `denylist_entries`* |
| **provider-sim** | `provider_payments`, `provider_payouts`, `provider_refunds`, `provider_callbacks`, `provider_outbound_callbacks`, `provider_failure_profile` |
| **webhook** | `webhook_endpoints`, `webhook_events`, `webhook_deliveries` |
| **engagement** | `notifications`, `report_facts`, `report_exports`, `audit_events`, `audit_exports` |
| **shared platform** | `idempotency_records`, `outbox_events`, `processed_events` — **per service, not shared** (see below) |

\* `denylist_entries` is owned by **risk**; payment reads it through the risk API
on the confirm path (it is already a synchronous call). It is listed under payment
only to note the read dependency.

**The three platform tables are not shared — they are per-service.** Every
service that emits events gets its *own* `outbox_events` and its own relay. Every
service that consumes gets its *own* `processed_events` inbox. Every service that
takes idempotent public writes gets its *own* `idempotency_records`. There is no
central outbox; that would be a shared database by another name and a single point
of failure on the money path.

### Breaking the foreign keys that cross a service boundary

Today the schema leans on referential integrity across capabilities. Those FKs
cannot survive the split — a foreign key cannot point into another database. The
cross-*capability* FKs that must break, and what replaces each:

| FK today | Crosses | Replacement |
|---|---|---|
| `orders (merchant_id, customer_id) → customers` | within payment | **Stays.** Order and Customer are the same service. |
| every `* → merchants(merchant_id)` | into merchant | **Replaced by a replicated read model.** See below. |
| ledger composite self-FKs, immutability/balance triggers | within ledger | **Stay.** The whole reason the Ledger goes last and whole. |
| `settlement_items → settlement_batches` | within settlement | Stays. |
| `webhook_deliveries → webhook_endpoints/webhook_events` | within webhook | Stays. |
| `payout_callbacks → payouts`, `refund_callbacks → refunds` | provider-sim → settlement/payment | **Replaced by event correlation** (the callback carries the id; no DB-level FK). |
| `user_roles → users` | within identity | Stays. |

The pattern: **FKs that were always within one capability stay and are free; FKs
that crossed a capability become either replicated reference data or event
correlation with an application-level guard.** The deferred `debits = credits`
trigger and the immutability triggers are the extreme case — they can only exist
inside one schema, which is the load-bearing argument for keeping the Ledger a
single service and extracting it last.

### Reference-data replication: the merchant read model

`merchant_id` is on nearly every table and nearly every service validates against
it. After the split, no service may FK to `merchants`. The answer is **replication
by event, not a synchronous call on every write**:

- The merchant service emits `merchant.registered`, `merchant.activated`,
  `merchant.suspended`, `merchant.closed` (it already has the outbox and the
  status-history table to source them from).
- Every service that needs to know a merchant exists and its status keeps a tiny
  **local `merchant_ref` read model** (`merchant_id`, `status`, `updated_at`),
  fed by those events through its own inbox. It is eventually consistent and that
  is acceptable: a just-registered merchant that hasn't propagated yet fails the
  create with a retryable error, and the client retries — the same shape as any
  at-least-once path.
- The format CHECK on `merchant_id` (`is_prefixed_id(..., 'mrc_')`) stays in every
  schema, because it never needed the foreign table — it only needs the shape.

This is the general rule for all cross-service reference data: **the owner emits
lifecycle events; consumers keep a narrow projection; the projection is a cache of
identity and status, never the authority.** It mirrors ADR-008's "consumer owns
the port" instinct, now backed by a table instead of a method call.

---

## How services talk

### Asynchronous is the default: Kafka from the existing outbox

The backbone is **Kafka (KRaft)**, and the producer side already exists as the
outbox relay. The change is the relay's sink.

- **Topics per aggregate, not per service.** `payment-events`, `refund-events`,
  `merchant-events`, `ledger-events`, `settlement-events`, … The event `type`
  field (already present) discriminates within a topic.
- **Partition key = the aggregate / tenant id** — `merchant_id` for
  merchant-scoped streams, the aggregate id (`pi_…`, `ord_…`) where per-aggregate
  ordering matters. This is what buys ordering *within* a key, which is the only
  ordering the money path needs (ADR-012 already reasoned about callback ordering
  per aggregate). Cross-key ordering is not promised and nothing may depend on it.
- **The event envelope is versioned.** The external contract gets an explicit
  schema version (`payment.succeeded` v1) and a compatibility rule: additive
  changes only within a version, a new version for a breaking change, both
  produced during a transition. This is the same internal-vs-external discipline
  Webhook already applies to its wire payloads (ADR-028) — now applied to every
  inter-service event.
- **Delivery is at-least-once. The inbox makes it idempotent.** Every consumer
  keeps writing to its own `processed_events` before acting, exactly as today.
  Duplicate delivery is a safe no-op; that property is already tested and does not
  change.

### Synchronous where a caller needs an answer *now*

Some interactions cannot be eventual. Payment's confirm needs a risk decision
before it acts (ADR-030: risk decides, payment acts). Settlement needs the
current available balance from the Ledger before it drafts a batch. These stay
**synchronous request/response**:

- **Through an API gateway** (SDD §7) for north-south (client → platform) traffic:
  authentication, rate limiting, routing. Internal east-west calls go
  service-to-service directly (or through a service mesh later; not yet).
- **Guarded by Resilience4j** — timeout, circuit breaker, bounded retry — because
  a synchronous dependency is a shared failure mode. The rule from the monolith
  holds: a downstream being down must degrade gracefully, never corrupt. Risk
  unreachable → payment applies its documented fail-open/fail-closed policy by
  tier (already specified for the Redis-down case; same policy, new cause). Ledger
  unreachable → settlement does not draft; it waits and retries. Nobody guesses a
  financial number because a service was slow.
- **The Ledger's write path is synchronous *and* eventual.** A caller (payment,
  refund, settlement) asks the Ledger to post a transaction via API; the Ledger
  commits its own schema (triggers enforce balance and immutability locally) and
  emits `ledger.transaction.posted`. Callers that only need to *know it posted*
  read the event; callers that need the *balance to act* call the API. The
  operational payment status and the authoritative balance stay separate, exactly
  as ADR-018 says — now across a wire.

### What stops being free

Stated plainly so no PR is surprised by it:

- **Cross-capability referential integrity.** Gone as a DB guarantee; replaced by
  replicated read models and application-level guards. A dangling reference is now
  *possible* between the event and its projection and must be handled as a
  retryable not-yet-consistent state, not a 500.
- **Cross-capability reads.** `OrderLookup`, `CustomerLookup`, `HoldingPeriodPolicy`
  stop being method calls into another module and become either a local
  event-fed projection or a synchronous API call. The port stays; the
  implementation behind it moves to a network.
- **The deferred `debits = credits` trigger and immutability triggers.** Cannot
  span databases. They stay inside the Ledger service, which is why nothing else
  may post to the ledger schema and why the Ledger goes last.
- **"Read your own write" across services.** A merchant registered in one service
  is not instantly visible in another. Eventual consistency is now a client-facing
  fact, surfaced as retryable errors and `asOf` timestamps (Reporting already does
  this — ADR-034).
- **A single `./mvnw test`.** The suite splits per service. `ModuleBoundaryTest`'s
  job — proving a boundary is not crossed — is inherited by the *absence of a
  dependency* (a service literally cannot import another's code) plus contract
  tests on the events.

---

## The PRs

Phase 3 is larger than Phase 2 and splits into five sub-phases. Migration numbers
continue the single sequence **only until 3A splits the histories**; from 3B on,
each service numbers its own migrations from V1 in its own schema, and this table
notes the *service* rather than a global V-number. ADRs continue the repo's single
sequence (ADR-036+).

| # | Branch | Delivers | Depends on | ADR |
|---|---|---|---|---|
| **3A — Foundations (still one deployable)** ||||
| 1 | `feature/kafka-backbone` | Kafka KRaft in compose + Testcontainers; envelope + versioning; relay gains a Kafka sink behind a flag | — | ADR-036 |
| 2 | `feature/dual-path-relay` | Relay publishes to Kafka *and* in-process dispatcher; consumers read from Kafka; inbox unchanged | 1 | ADR-037 |
| 3 | `feature/schema-per-service` | Split one schema into nine; per-service Flyway histories; per-service roles/grants; **no code leaves the process yet** | 2 | ADR-038 |
| 4 | `feature/merchant-ref-projection` | `merchant_ref` read model + merchant lifecycle events; consumers stop FKing to `merchants` | 3 | ADR-039 |
| 5 | `feature/api-gateway` | Gateway: auth, routing, rate limit; all north-south traffic through it | 2 | ADR-040 |
| **3B — Extraction wave 1: the pilot and the leaves** ||||
| 6 | `service/provider-sim` | **Pilot, built.** Provider Simulator becomes its own deployable + repo module | 3A | ADR-041 |
| 7 | `service/webhook` | Webhook service, Kafka-fed | 6 | ADR-042 |
| 8 | `service/engagement` | Notification + Reporting + Audit as one service; Audit's recorder becomes an event (see below) | 6 | ADR-043 |
| 9 | `service/risk` | Risk service; payment's confirm calls it synchronously via gateway/mesh | 6 | ADR-044 |
| **3C — Extraction wave 2: supporting core** ||||
| 10 | `service/identity` | Identity service; token issuance + validation others depend on | 3B | ADR-045 |
| 11 | `service/merchant` | Merchant + settlement-config service; authoritative source of `merchant_ref` | 4, 10 | ADR-046 |
| 12 | `service/settlement` | Settlement service; reads ledger balance via API, drives payouts | 11 | ADR-047 |
| **3D — Extraction wave 3: the money path** ||||
| 13 | `service/payment` | Order + Payment + Customer + Refund service | 3C | ADR-048 |
| 14 | `service/ledger` | **Last.** Ledger service; posting is now an API + `ledger.transaction.posted` event; triggers stay inside | 13 | ADR-049 |
| **3E — Close-out** ||||
| 15 | `chore/observability` | OpenTelemetry traces across service hops; Prometheus/Grafana; trace id on the money path | 3D | ADR-050 |
| 16 | `chore/decommission-monolith` | Remove the dual-path flag, the in-process dispatcher, the last shared code; the monolith is gone | 3D | ADR-051 |

Migration and ADR numbers are pre-assigned here for the same reason Phase 2
did it: parallel worktrees must not collide on them.

---

### 3A — Foundations

The whole of 3A ships **inside the still-single deployable**. Nothing is extracted.
The goal is to make extraction a deployment change rather than a code change: by
the end of 3A the monolith is publishing to Kafka, consuming from Kafka, split
into nine schemas with nine Flyway histories and nine roles, fronted by a gateway
— and still running as one process. This is the strangler-fig scaffold. If 3A is
right, every PR in 3B–3D is "lift this package into its own module and delete its
in-process wiring," not "rewrite how it talks."

#### PR 1 — Kafka backbone

**Branch:** `feature/kafka-backbone` · **ADR-036**

- **Goal:** a running Kafka (KRaft, single broker dev / 3-broker target) the app
  can produce to and consume from, with a versioned event envelope.
- **Includes:** Kafka in `docker-compose`; a Testcontainers `KafkaContainer` in
  the suite; the event envelope type (id, type, version, occurredAt, partition
  key, payload) formalized from what the outbox row already carries; topic naming
  and the version-compatibility rule written down.
- **DB:** none.
- **Comms:** the producing/consuming primitives only; no capability uses them yet.
- **Verification:** a round-trip test — produce an envelope, consume it, assert
  the payload and version survive.
- **Rollback:** the app runs without the Kafka container; the primitives are
  dormant.

#### PR 2 — Dual-path relay

**Branch:** `feature/dual-path-relay` · **ADR-037**

- **Goal:** every event that flows in-process today *also* flows through Kafka,
  and consumers read from Kafka — while the in-process path stays live as a safety
  net.
- **Includes:** the outbox relay gains a Kafka sink alongside the in-process
  dispatcher (behind a flag, default both); each `EventHandler` gets a Kafka
  listener adapter that feeds the same handler through the same `processed_events`
  inbox. Because the inbox already makes redelivery a no-op, running both paths is
  safe by construction.
- **DB:** none — the existing per-capability inbox is reused.
- **Comms:** at-least-once through Kafka, deduped by the inbox. Partition key set
  per aggregate/tenant.
- **Verification:** an event produced by one capability and consumed by another
  arrives once *effectively* (processed exactly once) with both paths on; the
  suite stays green.
- **Rollback:** flip the flag to in-process only.

#### PR 3 — Schema-per-service

**Branch:** `feature/schema-per-service` · **ADR-038**

- **Goal:** the 46 tables live in nine schemas, each with its own Flyway history
  and its own DB role, with no cross-schema FK left — **still one process.**
- **Includes:** move each capability's tables to its schema; split
  `flyway_schema_history` per schema; create per-service roles and grant each only
  its own schema; give each event-emitting capability its own `outbox_events` and
  relay, each consumer its own `processed_events`, each idempotent-write surface
  its own `idempotency_records`. Cross-capability FKs are dropped here (their
  replacements land in PR 4 and the wave PRs).
- **DB:** the big one. This is where `debits = credits` and the immutability
  triggers are confirmed to be wholly inside the ledger schema, and where every
  dropped FK is enumerated and matched to its replacement plan.
- **Comms:** unchanged (still dual-path in one process), but now each service's
  outbox is physically its own.
- **Verification:** the app boots with nine schemas and `ddl-auto=validate`; every
  integration test still passes; a test asserts each role cannot select another
  schema's table.
- **Rollback:** this is the hard-to-reverse PR. It ships behind a full backup and
  a tested down-path, and it is verified live on a populated dev DB (the V36
  playbook: apply forward, prove the app runs, prove a role is fenced) before
  merge.

#### PR 4 — Merchant reference projection

**Branch:** `feature/merchant-ref-projection` · **ADR-039**

- **Goal:** consumers stop depending on the `merchants` table and depend on a
  local, event-fed `merchant_ref` instead.
- **Includes:** merchant lifecycle events sourced from `merchant_status_history`;
  a `merchant_ref` table and inbox-fed updater in each consuming service; the
  merchant-exists/merchant-active checks rewritten to read the projection; the
  format CHECK kept, the FK gone.
- **DB:** `merchant_ref` per consuming schema.
- **Comms:** `merchant.*` events, at-least-once, deduped.
- **Verification:** register → activate a merchant; assert the projection updates
  and a scoped write succeeds; assert a not-yet-propagated merchant yields a
  retryable error, not a 500.
- **Rollback:** re-point the checks at the (still-present, pre-decommission)
  merchant table.

#### PR 5 — API gateway

**Branch:** `feature/api-gateway` · **ADR-040**

- **Goal:** one north-south entry point doing auth, routing and rate limiting, so
  that when services split there is already a stable front door.
- **Includes:** a Spring Cloud Gateway (or equivalent) deployable; JWT validation
  at the edge; route rules that today all point at the monolith and later
  re-point per service; rate limiting.
- **DB:** none.
- **Comms:** north-south only; east-west stays direct.
- **Verification:** every existing Postman request passes through the gateway
  unchanged; an unauthenticated call is refused at the edge.
- **Rollback:** clients address the monolith directly; the gateway is optional
  until 3B.

---

### 3B — Extraction wave 1: the pilot and the leaves

Now code leaves the process. Each PR here is the **strangler recipe** applied to
one service:

1. Confirm `ModuleBoundaryTest` shows a clean cross-capability boundary (leaves
   already do).
2. Lift the capability's packages into their own module/repo with their own
   `pom.xml`, their own `application.yaml`, their own schema + Flyway history
   (already carved in 3A).
3. Turn its lookup ports into Kafka listeners (for reads it can project) or
   gateway/mesh calls (for reads it must have fresh).
4. Give it its own inbox + idempotency (already carved in 3A).
5. Deploy it alongside the monolith; re-point the gateway route; turn off the
   monolith's in-process copy for that capability.

#### PR 6 — Provider Simulator (the pilot) — built

**Branch:** `service/provider-sim` · **ADR-041**

- **Goal:** prove the whole extraction recipe on the *safest possible* service —
  the one with an empty cross-capability allowlist in both directions and no money
  authority at all.
- **Built, and one thing corrected from this table's original prose.** "Outbound
  callbacks publish to Kafka" and "consumes provider-request events" describe a
  transport the simulator never had: ADR-017 built it as a scheduled dispatcher
  POSTing an HMAC-signed body directly at PayMesh's callback route, with **no
  outbox and no event consumption at all**, and it predates the Kafka backbone
  (ADR-036) by nineteen ADRs. The pilot's job was to prove the *process move* is
  cheap, not to also rewire a working, already-network-shaped protocol —
  bundling both would have put an unrelated redesign of ADR-012's ordering/dedup
  contract inside the PR meant to de-risk extraction itself. What actually
  shipped: the simulator as its own deployable (`provider-sim/`), its inbound
  `/sim/v1/**` reached directly (by gateway route or by a caller who knows its
  port), its outbound callbacks the same signed HTTP POST as before, now crossing
  a real process boundary instead of a loopback one. ADR-012's ordering/dedup is
  re-proven across that real wire in `provider-sim`'s own delivery test (a
  WireMock stub stands in for the monolith, mirroring how the gateway's tests
  already stand WireMock in for it).
- **DB:** the `simulator` schema (already carved in 3A), reached as the fenced
  `simulator_svc` role; its own Flyway history adopts the existing five tables
  rather than re-creating them.
- **Comms:** unchanged in shape — inbound is a direct HTTP call to `/sim/v1/**`,
  outbound is a signed HTTP POST to the monolith's callback route; correlation by
  id (the FK to payouts/refunds is already gone).
- **Verification:** `provider-sim`'s own delivery test proves signing, retry,
  duplicate and out-of-order handling against a stubbed receiver; the monolith's
  `ProviderCallbackApiTest`/`ProviderCallbackIntegrationTest` independently prove
  a signed callback still moves a payment intent to `SUCCEEDED` (hand-signed,
  same pattern ADR-019 uses for Refund) — the two together cover what one
  single-process test used to, now that no one test can compile both sides.
- **Rollback:** re-point the gateway's `provider-sim-uri` at a monolith still
  carrying the old package (preserved in git history, not deployed alongside).
  Not a flag flip: the dual-path relay (ADR-037) was never in this module's path,
  since the simulator never spoke Kafka to begin with.
- **Why first:** if the recipe is wrong, it is wrong here, where nothing
  financial can be lost while we find out.

#### PR 7 — Webhook

**Branch:** `service/webhook` · **ADR-042**

- **Goal:** the first *merchant-facing* leaf out of the process.
- **Includes:** webhook service, Kafka-fed from `payment.*`/`refund.*`/`order.*`;
  per-tenant secret derivation stays exactly as ADR-028 built it (nothing to
  encrypt, nothing to migrate); its delivery dispatcher runs on its own timer.
- **DB:** the `webhook_*` schema.
- **Comms:** consumes domain events; makes outbound HTTP to merchants.
- **Verification:** a payment event produces a signed delivery from the separate
  service; a merchant endpoint being down never touches a payment (the invariant,
  now literally in another process).
- **Rollback:** dual path.

#### PR 8 — Engagement (Notification + Reporting + Audit)

**Branch:** `service/engagement` · **ADR-043**

- **Goal:** move the three read-side consumers out together.
- **Includes:** the three capabilities in one deployable; Notification and
  Reporting are already pure event consumers and move cleanly; Reporting keeps its
  `asOf` eventual-consistency contract (ADR-034), now genuinely across services.
- **The Audit exception:** Audit today is *not* a pure consumer — its subjects
  (merchant freeze, role grant, secret rotation) emit no domain event, so
  `AuditRecorder` is called **in-process, inside the acting transaction** (ADR-035),
  which is the whole point of the design: the audit row commits with the action.
  Once the acting capability is in another service, that in-process call cannot
  survive. The replacement preserves the guarantee, not the mechanism: each
  privileged action **emits an `*.audited`-style event on its own outbox, in the
  same local transaction as the action** (so a committed freeze still atomically
  carries its audit fact), and the engagement service consumes it into
  `audit_events` through its inbox. The immutability trigger stays in the audit
  schema. The atomicity moves from "same DB transaction as the action" to "same
  outbox transaction as the action" — which is exactly the guarantee the outbox
  was built to give, and strictly stronger than a synchronous audit call that
  could fail after the action committed.
- **DB:** `notifications`, `report_*`, `audit_*` as the engagement schema.
- **Comms:** consumes domain events *and* new `*.audited` events; emits none the
  money path reads.
- **Verification:** a merchant suspension in the (still-monolith) merchant
  capability produces an immutable audit row in the separate engagement service,
  with the before/after hashes intact; killing engagement does not roll back the
  suspension.
- **Rollback:** dual path; the in-process `AuditRecorder` stays available until
  the merchant/identity/webhook producers are themselves extracted and switched to
  the event.

#### PR 9 — Risk

**Branch:** `service/risk` · **ADR-044**

- **Goal:** the first *synchronous* extraction — payment's confirm must get a risk
  decision over the network.
- **Includes:** risk service; `denylist_entries` moves with it; payment's confirm
  path calls it via gateway/mesh, wrapped in Resilience4j; the fail-open/fail-closed
  policy by tier (already specified for the Redis-down case, ADR-030) now also
  covers "risk service unreachable."
- **DB:** the `risk` schema.
- **Comms:** synchronous request/response on confirm; consumes events for velocity
  features.
- **Verification:** confirm gets a real decision from the separate service; a risk
  timeout applies the documented policy and records that it did — payments do not
  hang and do not silently allow.
- **Rollback:** dual path; in-process risk stays until this is proven.

---

### 3C — Extraction wave 2: supporting core

#### PR 10 — Identity

**Branch:** `service/identity` · **ADR-045**

- **Goal:** authentication becomes its own service, since every other service
  depends on validating its tokens.
- **Includes:** identity service; token *issuance* here, token *validation* at the
  gateway and in each service via the shared JWT verification (public key /
  introspection); `security_events` and `audit`'s role-grant events emitted from
  here.
- **DB:** the `identity` schema.
- **Comms:** synchronous for login/refresh; events for role changes and audit.
- **Verification:** a token minted by the identity service authorizes a call
  routed by the gateway to another service; a revoked credential is refused.
- **Rollback:** dual path.

#### PR 11 — Merchant

**Branch:** `service/merchant` · **ADR-046**

- **Goal:** the authoritative source of `merchant_ref` becomes its own service,
  closing the loop opened in PR 4.
- **Includes:** merchant + settlement-config service; it is now the sole emitter
  of `merchant.*` lifecycle events that every `merchant_ref` projection consumes;
  its privileged actions (freeze/activate/close) emit the `*.audited` event PR 8
  defined.
- **DB:** the `merchant` schema.
- **Comms:** emits merchant lifecycle + audit events; serves synchronous
  merchant-detail reads for the few paths that need fresh data.
- **Verification:** register/activate/suspend propagate to every consumer's
  projection; the audit row lands in engagement.
- **Rollback:** dual path.

#### PR 12 — Settlement

**Branch:** `service/settlement` · **ADR-047**

- **Goal:** settlement runs on its own, reading the ledger balance across the wire.
- **Includes:** settlement service; it calls the (still-monolith) Ledger's
  available-balance API synchronously to draft a batch, and drives provider payouts
  through the now-separate simulator by event; its batch-net-equals-items invariant
  stays a deferred trigger *inside the settlement schema* (it never crossed a
  boundary).
- **DB:** the `settlement` schema (`payout_callbacks`/`refund_callbacks` correlate
  by id).
- **Comms:** synchronous read of ledger balance; events to/from provider-sim;
  requests ledger postings via the Ledger API (final-payout-failure reversal is a
  new ledger transaction, ADR-032, now an API call).
- **Verification:** a batch drafts against a live balance read, pays out through
  the separate simulator, and a forced payout failure posts a reversal via the
  Ledger API.
- **Rollback:** dual path.

---

### 3D — Extraction wave 3: the money path

#### PR 13 — Payment (Order + Payment + Customer + Refund)

**Branch:** `service/payment` · **ADR-048**

- **Goal:** the synchronous money path leaves the process as one service — kept
  together so there is no network hop between order, payment and refund, which
  share the tightest transactional coupling in the system.
- **Includes:** the four capabilities in one deployable; confirm calls risk
  (PR 9) and, to post money, calls the Ledger API and consumes
  `ledger.transaction.posted`; refunds that hit an already-released balance drive
  the Ledger's `MERCHANT_AVAILABLE` debit through the API (ADR-031's sharp edge,
  now a network call).
- **DB:** the `payment` schema (customers, orders, payments, refunds).
- **Comms:** synchronous to risk and ledger; emits `payment.*`/`refund.*`/`order.*`
  events every leaf already consumes.
- **Verification:** a full authorize→confirm→capture→refund flow across payment,
  risk, ledger and simulator, with the ledger as the authority and payment status
  as operational state — the ADR-018 separation proven across services.
- **Rollback:** dual path — but this is the point of no easy return; it ships only
  after 3A–3C are stable in production-shaped testing.

#### PR 14 — Ledger (last)

**Branch:** `service/ledger` · **ADR-049**

- **Goal:** the financial source of truth becomes its own service **last and
  whole**, with its invariants intact because they never leave its schema.
- **Includes:** ledger service; posting a transaction is now an idempotent API
  (`Idempotency-Key` scoped per caller+action, ADR-009) plus a
  `ledger.transaction.posted` event; the deferred `debits = credits` trigger and
  the entry/transaction immutability triggers stay exactly as V15/V18 wrote them,
  inside the ledger schema; balance reads are an API; the pending→available release
  job (ADR-031) runs here on its own timer.
- **DB:** the `ledger` schema — untouched in structure, which is the whole reason
  it went last.
- **Comms:** synchronous posting + balance-read API (idempotent); emits
  `ledger.*` events.
- **Verification:** a posting requested twice with the same key posts once (inbox +
  idempotency); debits≠credits is refused *by the trigger* across the API; an
  attempted entry edit is refused by the immutability trigger — the same tests
  `LedgerIntegrationTest` runs today, now against the service.
- **Rollback:** the gravest one; dual path stays until the money path has run
  clean against the separate ledger under load.
- **Why last:** every earlier service could tolerate eventual consistency or a
  brief unavailability. The Ledger cannot tolerate a distributed transaction, and
  its invariants cannot be distributed. Keeping it whole and extracting it after
  everything that calls it is already stable is the only ordering that protects the
  governing invariant.

---

### 3E — Close-out

#### PR 15 — Observability across the hops

**Branch:** `chore/observability` · **ADR-050**

- **Goal:** a single trace follows a request across every service it touches, so
  the money path is debuggable when it spans nine deployables.
- **Includes:** OpenTelemetry auto-instrumentation; trace/span propagation through
  the gateway, synchronous calls and Kafka headers; Prometheus metrics; Grafana
  dashboards; a trace id stamped on every money-path log line.
- **Verification:** one authorize→…→ledger flow shows as one connected trace
  across services.

#### PR 16 — Decommission the monolith

**Branch:** `chore/decommission-monolith` · **ADR-051**

- **Goal:** remove the scaffolding once every capability runs as its own service.
- **Includes:** delete the dual-path flag and the in-process `EventDispatcher`;
  delete the shared in-process lookup adapters; delete the now-empty monolith
  module; keep `shared/` only as versioned libraries (envelope, id value objects,
  security primitives) each service depends on explicitly.
- **Verification:** the monolith deployable no longer exists; every Postman flow
  passes against the service topology through the gateway; the per-service suites
  are green.
- **Rollback:** the last PR before this is the last safe point; after decommission,
  forward-only.

---

## Prerequisites, stated once

- **Kafka (KRaft)** — no ZooKeeper. Single broker in dev, three in the target.
- **An API gateway** — Spring Cloud Gateway or equivalent (SDD §7).
- **Containers per service** — each deployable gets a Dockerfile; local dev runs
  the set under `docker-compose`, the target runs on Kubernetes/Helm (SDD §22),
  which is its own later chore and deliberately not a PR here.
- **Resilience4j** on every synchronous hop.
- **OpenTelemetry** — deferred to 3E on purpose; you cannot usefully trace hops
  that do not exist yet.

## Risks and how the ordering answers them

- **A split that loses or duplicates money.** Answered by: never a distributed
  transaction; the inbox on every consumer; the Ledger extracted last and whole
  with its triggers intact; the dual-path relay so every step is reversible until
  proven.
- **Eventual consistency surfacing as bugs.** Answered by: making it explicit —
  retryable not-yet-consistent errors, `asOf` on reads, replicated reference data
  that is openly a cache.
- **A schema split gone wrong.** Answered by: doing it (PR 3) *before* any
  extraction, in one process, behind a backup and a tested down-path, verified
  live on a populated DB.
- **Big-bang risk.** Answered by the strangler shape: 3A builds the scaffold with
  zero services extracted; each later PR moves exactly one service and is
  independently reversible via the dual path.

## Working method, per PR

The method that carried Phase 1 and Phase 2, unchanged, because it worked:

1. A design spec (an ADR) written and approved before implementation, corrected in
   the spec when it turns out wrong — not just in the code.
2. One service (or one foundation step) per branch, one focused change per PR.
3. Verified live before merge, including the Postman collection through the
   gateway — the Java suites cannot see cross-service HTTP-surface regressions.
4. Nothing merges on the author's report. An independent reviewer re-runs the
   suites and, where a test protects an invariant, breaks the implementation to
   confirm the test catches it.
5. Every non-obvious tradeoff gets an ADR while the reasoning is fresh.

---

## Instructions for the executing agent (Claude Code)

This section is addressed to the Claude Code agent that will implement this plan.
Follow it literally; it is the process, not a suggestion.

### The per-PR loop

Work **one PR at a time, in the table order**, and never start the next before the
current one is merged. For each PR:

For each PR:

1. Read the PR section and relevant existing ADR.
2. Inspect only the affected capability and direct dependencies.
3. Produce a <=10-line implementation plan.
4. Implement directly.
5. Run targeted tests.
6. Run the full relevant suite once before completion.
7. Use /code-review once after implementation.

Never spawn subagents unless explicitly requested.
Do not use /office-hours by default.

### Keep the living docs current — every PR, not at the end

These three are the project's memory. A PR is not done until they match reality:

- **`docs/project-status.md`** — the authoritative "pick up here" doc. After each
  PR, update it: the service topology as it actually stands, which capabilities
  have left the monolith, the test count, the migration state per service, the ADR
  count, and the "what of the SDD is implemented" mapping. This doc is known to go
  stale — do not let it. Verify it against the code before writing, never from
  memory.
- **The Postman collection** — update it **when the HTTP surface changes**: a new
  gateway route, a service that now answers on a different path, a new endpoint, a
  changed request/response shape. The Java suites cannot see HTTP-surface
  regressions, so an out-of-date collection is a blind spot on the money path.
  When a PR does not touch the HTTP surface, say so and skip it — do not churn it
  for nothing.
- **The `README`** (and `CLAUDE.md` where it describes the architecture) — keep the
  "what this is / how to run it" accurate as one process becomes many: the compose
  services, the run commands, the topology diagram. A reader who follows a stale
  README and connects to the wrong thing is a real failure mode here.

Update these **in the same PR** that makes the change true, not in a trailing
docs-only PR. A code change and its doc are one focused change.

### Token discipline

The `/ponytail:ponytail` skill is **on for the whole of this phase.** It is the
standing instruction against over-engineering, and this plan is exactly where the
temptation lives — nine services invite nine of everything. Apply it:

- **Climb the ladder before writing.** Does this abstraction need to exist? Is
  there already a helper/port/pattern in the codebase (there usually is — this
  system was built for this split)? Reuse the outbox, the inbox, the lookup ports,
  the `is_prefixed_id` CHECK, the timer shape, before inventing.
- **Fewest files, shortest working diff — after understanding the change.** No
  interface with one implementation, no config for a value that never changes, no
  scaffolding "for later." Later can scaffold for itself.
- **Read before you spend tokens.** Read the capability you are extracting and its
  `ModuleBoundaryTest` allowlist fully, once, before editing — a confident wrong
  change is more expensive than the read. But do not re-read files already in
  context, do not re-derive facts this document already states, and do not narrate
  options you will not take. Act when you have enough to act.
- **Do not spawn subagents unless explicitly asked.** Each spawn re-derives context
  you already hold; handle the work inline with your own tools.
- **Mark deliberate corner-cuts** with a `ponytail:` comment naming the ceiling and
  the upgrade path, exactly as the code already does — so a cut is a recorded
  decision, not a silent gap.

The measure of a good PR here is not how much it builds; it is how little it adds
to move exactly one service out of the process without weakening the governing
invariant.
