# PayMesh — Project Status

_Last updated: 6 September 2026._

This is the pick-up-here document: what's built, what each PR decided and what it cost, phase by
phase, ending with where to start next. Full ADRs live in `docs/decisions/`; this is the
compressed version — decision + tradeoff, not the whole argument. For the target architecture read
the SDD; for the Phase 3 plan of record read `docs/phase-3-microservices-extraction-plan.md`.

---

## Phase 1 — Monolith core (complete)

One deployable, package-by-feature (ADR-002), built as strangler-ready modules from the start
(ADR-001) so extraction later is a lift, not a rewrite.

### Foundations

- **ADR-001 — Start as a modular monolith.** Decision: one Spring Boot app, business modules with
  explicit boundaries, extraction deferred until a module has a stable interface and its own
  scaling/reliability needs. Tradeoff: modules can't deploy independently yet, and the whole bet
  only pays off if boundary discipline holds without a compiler enforcing it module-to-module.
- **ADR-002 — Package by feature.** Decision: each capability owns `api/application/domain/infrastructure`
  under its own top-level package; no global `controller`/`service`/`repository` packages.
  Tradeoff: none stated — this is the one decision with no cost, only the discipline of not
  reaching for the global-layer shortcut later.
- **ADR-003 — Opaque prefixed identifiers.** Decision: public ids are `<prefix>_<uuid>`
  (`mrc_`, `cus_`, `ord_`…), minted by the app, validated in a value-object's compact constructor,
  never a sequential DB key. Tradeoff: 40 characters per id instead of a few, a `VARCHAR(40)` PK,
  and random UUIDs scatter B-tree inserts (mitigated later, if it matters, by UUIDv7 behind the
  same type). Unguessability is defense in depth only — every query is still tenant-scoped.
- **ADR-004 — Separate JPA entity from domain aggregate.** Decision: two types per aggregate (a
  framework-free domain type, a `<Aggregate>JpaEntity`) plus a hand-written mapper — no MapStruct,
  no `@Entity` on the domain. Tradeoff: two types and a mapper to keep in sync per aggregate, paid
  for a domain layer that's plain Java and testable with no Spring context.
- **ADR-005 — Testcontainers, not a developer database.** Decision: every context-loading test runs
  against a throwaway PostgreSQL container (`ddl-auto=validate` needs a real, matching schema).
  Tradeoff: Docker becomes a hard test prerequisite and cold runs pay ~1 minute of container
  startup; in exchange CI needs no database service and migrations are re-proved on every run.
- **ADR-029 — Constrain identifier formats in the database.** Decision: 63 `CHECK` constraints
  (one `IMMUTABLE` SQL function, `is_prefixed_id`) enforce ADR-003's shape on every id column the
  app mints, closing a hole where five scheduled sweeps could be permanently disabled by one
  malformed row outside their per-item `try/catch`. Tradeoff: this narrows what can break a row
  mapper, it doesn't close the set — JSONB metadata columns still have no shape constraint, so the
  per-item `try/catch` stays load-bearing regardless.

### Merchant — `com.paymesh.merchant`

Delivers self-service onboarding (`POST /api/v1/merchants`, public) and a merchant read scoped to
callers holding a role there. Domain normalizes on the way in and `uq_merchants_email` is the real
uniqueness guard, not the application's `existsByEmail` pre-check.

- **ADR-022 — Authenticate machines with merchant API credentials.** Decision:
  `Authorization: ApiKey ak_<prefix>.<secret>`, verified inside the Spring Security filter chain
  (must run *before* `BearerTokenAuthenticationFilter`, or every ApiKey request 401s before the
  filter sees it), minting an in-memory unsigned JWT so a key is indistinguishable downstream from
  a human token — every rule written for humans (status gate, roles) applies to machines for free.
  Tradeoff: SHA-256, not bcrypt, for the secret hash — correct because the secret is 32 random
  bytes and not guessable, but a reviewer expecting bcrypt everywhere has to know why this one
  differs. No key expiry exists; rotation is a manual discipline.

### Identity & Access — `com.paymesh.identity`

Delivers register/login/refresh/logout: 15-minute HS256 access tokens, 30-day opaque refresh
tokens, rotation that detects reuse and revokes the whole token family.

- **ADR-007 — Authenticate at the filter chain, scope tenancy at the data.** Decision: default-deny
  (`anyRequest().authenticated()`) with an explicit public-route allowlist; a verified token becomes
  an `AuthenticatedCaller` that controllers must ask for, so no request body/path/query ever carries
  a merchant id — the type signature carries tenancy, not developer discipline. Cross-tenant access
  is always 404, never 403 (a 403 would confirm the row exists). Tradeoff: roles are baked into the
  token at login, so a role granted mid-session isn't visible for up to 15 minutes, and there's no
  access-token revocation before expiry — the token's lifetime *is* the revocation window.
- **ADR-020 — Defer federated login.** Decision: no OAuth2/OIDC; password login is the only human
  auth path until there's an actual identity provider to integrate with. Tradeoff: a stub endpoint
  or fake IdP were both rejected as worse than absence — a login route that authenticates nobody is
  a bypass waiting to be found in review — so this is a stated gap, not a hidden one.

### Customer — `com.paymesh.customer`

Delivers per-merchant customer records; `merchant_reference` unique per merchant, not globally.

- **ADR-006 — Defer PII encryption, ship the encrypted-shape schema.** Decision: display columns
  (`email`, `phone`) and separate deterministic-hash lookup columns from day one, so introducing
  real encryption later only changes what the display column *contains* — no query, index or
  repository method changes. Tradeoff: **accepted, stated risk**: PII is plaintext today (unsalted
  SHA-256 lookup hashes are additionally dictionary-reversible), acceptable only because PayMesh
  handles no real people. Five concrete prerequisites are listed before this can touch a real
  person's data (KMS, envelope encryption, HMAC-under-pepper hashing, audited decrypt, retention).

### Merchant + Identity + Customer — lifecycle correctness

Three successive corrections of the same defect, worth reading as one narrative: lifecycle enums
that were declared but never reachable.

- **ADR-021 — Make the lifecycle states reachable, and enforce them.** Decision: intent methods on
  all three aggregates, a status-history table for each, one `MerchantStatusGate` filter (not a
  check duplicated per service) refusing writes from a non-`ACTIVE` merchant, and `CallerRole`
  finally read (not just tenant scope) from the token. KYC ships in the *same* PR — shipping the
  gate without it froze every newly registered merchant permanently, caught by 84 failing tests
  before merge. Tradeoff: self-serve registration is no longer really self-serve (needs platform
  approval to trade); provider/refund callbacks and the lifecycle-transition endpoints themselves
  are deliberately exempt from the gate, or suspension would be irreversible.
- **ADR-023 — Finish the lifecycle claims; give the token table a writer.** Decision: ADR-021's
  claim that users and customers could be disabled wasn't true — the aggregate methods existed and
  nothing called them. This PR wires `Customer.block/unblock` for real, gives `payment_method_tokens`
  its first writer (attach/list/detach, storing only a provider *reference*, never a PAN), and adds
  a `PROCESSING`-refund timeout mirroring Payment's. Tradeoff: the refund timeout can be *wrong* — it
  fails a refund to `FAILED` on a clock, and if the provider actually moved the money, PayMesh now
  believes it didn't; `UserStatus.SUSPENDED/CLOSED` stays unreachable, deferred again because "who
  may disable a user" needed its own answer.
- **ADR-024 — Disabling people, at the two scopes that mean different things.** Decision: splits
  "remove this employee from my merchant" (a `MERCHANT_ADMIN` action, revokes only `user_roles` at
  that tenant) from "bar this person from the whole platform" (`PLATFORM_ADMIN`-only, moves
  `UserStatus`) — conflating them would have let one merchant lock a user out of a *different*
  merchant they also work for. Grant ships with revoke, or an admin who revoked by mistake needs a
  support ticket. Tradeoff: granting a role needs no consent from the user (grant-by-id, not an
  invitation flow); platform-scoped actions can't be idempotency-keyed because that table's schema
  assumes every action belongs to a merchant.

### Order — `com.paymesh.order`

Delivers create/read/list/cancel with every status in the enum reachable, cursor pagination that
breaks ties on `order_id` (dropping the tiebreak silently skips rows sharing a timestamp boundary).

- **ADR-008 — Cross-module reads through a consumer-owned port.** Decision: Order defines the
  narrow interface it needs (`CustomerLookup.exists(...)`) in its *own* package; Customer never
  knows Order exists. The one-implementation-interface rule is deliberately broken here because the
  substitution (Customer becomes its own service) is the actual roadmap, not a hypothetical.
  Tradeoff: the check is advisory — a composite FK on `(merchant_id, customer_id)` is what actually
  stops an order naming another tenant's customer; a single-column FK would have let it through with
  only application code standing guard.
- **ADR-013 — Re-read payability at confirm; lock the order at create.** Decision: an order could be
  cancelled after its live payment intent was created, then the intent confirmed anyway — collecting
  for a cancelled order. Confirm now re-reads payability inside its own transaction (a plain read,
  not a lock — locking there serializes concurrent confirms and turns a clean 409 into a 500 on a
  different constraint); create takes `SELECT … FOR UPDATE` on the order row. Tradeoff: a real,
  named, *unclosed* window remains — a cancel landing between confirm's read and its commit — bounded
  only by there being no live provider to actually move money yet.
- **ADR-014 — Expire orders, never one holding a live collection.** Decision: a sweeper skips any
  order with a live payment intent, asking Payment through an Order-owned inverted port
  (`PaymentActivityLookup`, implemented by Payment) so the dependency graph stays acyclic — the naive
  shape would have made Order → Payment → Order, undoing the whole point of separable modules. Both
  sides take the same order-row lock so create and expire serialize correctly. Tradeoff: expiry is
  now indefinitely deferred while a collection is live — `expires_at` means "expires at or after this
  instant, once nothing is collecting," not an instant guarantee, and that had to wait on ADR-015 to
  actually bound it.

### Payment — `com.paymesh.payment`

Delivers payment intents with the exact-amount rule making overpayment structurally impossible, not
merely CHECK-constrained.

- **ADR-011 — One live payment intent per order, enforced by a partial unique index.** Decision:
  `uq_payment_intents_live_per_order` excludes only `FAILED`/`CANCELLED` — the index *is* the
  enforcement, the application pre-check exists only to produce a friendly 409 before the constraint
  would. Tradeoff: split/partial payments are ruled out as a direct consequence, and `PROCESSING` is
  deliberately uncancellable (an in-flight attempt may have already succeeded at the provider) —
  which means a lost callback strands the order's only slot with no route out until ADR-015.
- **ADR-012 — Deduplicate and order provider callbacks with three independent mechanisms.**
  Decision: a primary key on `(provider, external_event_id)` — deliberately **not** merchant-leading,
  because the merchant is derived from the callback, not supplied by it, and adding it would let one
  provider event apply once per resolvable merchant — inserted *inside* the transition's own
  transaction (so a concurrent duplicate blocks rather than racing), plus a monotonic
  per-attempt-max event clock for ordering. Tradeoff: ties (identical `occurred_at`) are refused
  rather than applied, which trades "moved backwards" for "never moved forward" — a real, accepted
  hole pending a provider sequence number that doesn't exist yet.
- **ADR-015 — Time a stranded `PROCESSING` payment out to `FAILED`.** Decision: after a generous
  default of 1 hour, an intent stuck with no provider answer is asserted `FAILED` with a code
  (`provider_no_response`) that must never look like a decline, releasing the order's slot.
  Tradeoff: stated as the least certain decision in the whole payment capability — **a real payment
  can be recorded as failed** if it actually succeeded at the provider, letting the merchant
  double-collect on a second attempt. The age is "doing all the work" until reconciliation exists to
  catch it (ADR-026 later closes this).

### Provider Simulator — `com.paymesh.simulator`

- **ADR-017 — Simulate providers through scheduled, signed callbacks, never an inline call.**
  Decision: the simulator holds zero references to PayMesh in either direction (`ModuleBoundaryTest`
  empty allowlist both ways) — it restates PayMesh's callback contract rather than importing it, so
  the two can drift and a test catches it rather than a shared type silently keeping them in sync. A
  `@Scheduled` dispatcher, never an inline POST from the create handler, because every failure mode
  worth simulating (delayed, lost, duplicate, out-of-order) is a property of *when* a callback
  arrives. Authenticated by a third, deliberately *weaker* shared key than the callback route's HMAC.
  Tradeoff: the contract duplication is a real, paid cost (drift is possible and only a test catches
  it); percentage-based random failure injection is deliberately not built — a probabilistic path in
  a suite run on every commit is a flake generator.

### Ledger — `com.paymesh.ledger`

- **ADR-018 — Post the ledger from events; keep the invariants in the database.** Decision: the
  financial source of truth is built as double-entry accounts/transactions/entries with every
  invariant as a Postgres trigger or constraint — debits-equal-credits is a DEFERRED constraint
  trigger checked at COMMIT, entries are immutable by trigger — and the *only* writer is an event
  consumer of `payment.succeeded`, deliberately with no internal posting API (SDD §15.3), so every
  journal traces to a committed state change. Tradeoff: no platform fee (there's no fee schedule to
  post against) and no `account_balances` projection — the balance is a live `SUM` over entries,
  bounded but not free, carrying a `ponytail:` marker for the day it needs an index-only path instead.

### Refund — `com.paymesh.refund`

- **ADR-019 — Refunds own their callback route; over-refund is guarded by a lock and a trigger.**
  Decision: Refund gets its own `/internal/v1/refund-callbacks/{provider}` (sharing Payment's would
  make Payment know refunds exist), and over-refunding is stopped by a row lock on the payment taken
  *before* head-room is read — a deferred constraint trigger alone was tried first and **measured to
  fail**: its query runs on the snapshot of the statement that queued it, so two concurrent full
  refunds both passed in a live test before the lock existed. Tradeoff: the simulator still can't
  send refund callbacks (built for payments only), so refund callbacks are hand-signed HMAC in tests
  and Postman; nothing reconciles a lost refund callback until later.

### Platform plumbing — outbox, idempotency, event delivery

The infrastructure every capability above depends on, built to be broker-shaped before there was a
broker.

- **ADR-009 — Idempotency for public writes, PostgreSQL as sole authority.** Decision:
  `idempotency_records` keyed `(merchant, endpoint, key)`, the INSERT itself is the concurrency
  control (committed *before* the handler runs, so two retries collide on the primary key and the
  database picks the winner) — Redis rejected as authority because a cache eviction or split-brain
  could re-open an already-used key. A 5xx **deletes** the record (server doesn't know what it did,
  so a retry must be a real retry); a 4xx stores and replays it. Tradeoff: openly accepted — a retry
  after a 500 may duplicate an effect that actually committed; narrowing that further is the outbox's
  job, not this layer's.
- **ADR-010 — Transactional outbox in PostgreSQL, in the caller's transaction, no relay yet.**
  Decision: `OutboxWriter.append` assumes an open transaction and never opens its own — the caller
  wraps state-change + event-append in one `TransactionTemplate` (not `@Transactional`, which
  measurably fails to proxy this codebase's `final`, hand-wired application services). This PR
  deliberately stops at "written," with no relay, no Kafka, no inbox — a named safe state, not an
  omission. Tradeoff: a service that forgets to wrap its two writes compiles, starts, and passes
  every happy-path test — nothing catches a missing `TransactionTemplate` except a dedicated
  rollback test per producer, which is now a house requirement.
- **ADR-016 — Deliver events in-process, on a broker-shaped consumer contract, before Kafka.**
  Decision: `EventDispatcher` calls handlers directly (no broker, no queue) but `EventHandler` takes
  an envelope with a `Map` payload, dedupes via `processed_events`, and must throw to retry — exactly
  what a Kafka listener would need, so swapping the transport later changes no handler. One
  transaction per (handler, event), not per event, so one consumer failing never rolls back another's
  already-committed work. Tradeoff: an event that fails forever freezes its own aggregate's later
  events forever — no dead-letter, no attempt counter, no alert — named at the time as "the largest
  known hole in this change" and left open on purpose for ADR-025 to close.
- **ADR-025 — Give up on an outbox event rather than freezing its aggregate.** Decision: four new
  columns (`attempt_count`, `last_attempt_at`, `last_error`, `dead_lettered_at`) on `outbox_events`;
  after 25 attempts (~1 minute at the relay's 2s interval) a row is dead-lettered and the claim query
  skips it, so the aggregate behind it drains on the very next pass. Surfaced via `/actuator/health`
  going DOWN, not a metrics pipeline that doesn't exist. Tradeoff: **a dead-lettered event is never
  delivered** — stated without softening — and the health indicator must never be wired to a
  liveness/readiness probe, since restarting the process doesn't clear a dead letter and draining an
  instance mid-backlog is actively counterproductive.
- **ADR-026 — Reconcile against the provider's record by replaying it.** Decision: a scheduled job
  fetches the provider's daily export and **replays** every terminal row through the same callback
  service a real callback uses — no diff logic, no second copy of the state-machine rules, because a
  second copy is exactly what drifts. Deduplication key is the job's own deterministic hash so
  reruns are safe. One narrow, explicitly-scoped exception is added to ADR-012's "terminal states
  absorb": a payment ADR-015 guessed-`FAILED` can now be revived by the provider's own confirming
  record. Tradeoff: this is the change that finally closes ADR-015's and ADR-023's admitted
  uncertainty, but only for payments/refunds within a 3-day replay window, and only for this
  provider's specific "unknown status" semantics — a real acquirer's "unknown" must never be read the
  same way.

**Phase 1 status: done**, including this operational half.

---

## Phase 2 — Event-driven capabilities (complete)

Eight PRs, all merged. See `docs/phase-2-plan.md` for the sequencing rationale (Ledger-balance
work had to precede Settlement; Reporting was sequenced after Settlement so it lands settlement
facts in one pass).

- **PR 0 — Make `PLATFORM_ADMIN` grantable.** *(ADR-027)* Delivers: the fix that makes every other
  Phase 2 PR verifiable end to end — before this, a merchant registered through the public endpoint
  could never be activated, because activation is `PLATFORM_ADMIN`-only and no such role could ever
  exist. Decision: drop the `user_roles` primary key, make `merchant_id` nullable with **`NULL`
  meaning platform-wide**, and enforce the shape with two partial unique indexes plus a biconditional
  CHECK (`PLATFORM_ADMIN` iff `merchant_id IS NULL`) — closing off the escalation path where a
  merchant admin granting themselves the role at their own tenant would become platform staff.
  Tradeoff: demoting the platform's last admin needs a `FOR UPDATE`-locked count, not a CHECK or a
  deferred trigger, because two concurrent demotions of the last two admins can each read "2 left"
  and both pass under a single-row constraint — a genuinely serialized read, not a database rule.

- **PR 1 — Webhook.** *(ADR-028)* Delivers: merchant-facing endpoints, HMAC-signed delivery with
  backoff (1m/5m/30m/2h/6h, six attempts, 8h36m total — an arithmetic bug once shipped this at
  2h36m and a regression test now pins the total), and replay. Decision: the signing secret is
  **derived, never stored** — `HMAC-SHA256(masterKey, "paymesh.webhook.v1|endpointId|version")`, one
  block of HKDF-Expand (JDK 21 has no native HKDF) — so a database dump contains nothing that lets an
  attacker sign as PayMesh; rotation is an integer increment, not a re-encryption. Tradeoff: create
  and rotate are deliberately **not** idempotency-filtered, because that layer persists response
  bodies verbatim and would write a plaintext secret to `idempotency_records`; a lost create response
  is recovered by rotating, not by retrying create.

- **PR 2 — Risk.** *(ADR-030)* Delivers: synchronous risk evaluation on confirm, an immutable
  assessment recording the ruleset version and a verbatim feature snapshot. Decision: **Risk decides,
  Payment acts** — Risk writes nothing to any payment table and emits no event, because a second
  writer of Payment's state machine is how a status becomes unexplainable. Rules are code, not a
  `risk_rules` table (a bad expression at runtime is worse than a bad deploy); no Redis velocity
  counters (Postgres already has the rows). Tradeoff: evaluation runs **before** confirm opens its
  transaction, which looks like the weaker placement and is the correct one — `REQUIRES_NEW` was
  tried first and would have held two Hikari connections per confirm, which wedges the whole pool at
  ~5 concurrent confirms on the default size-10 pool.

- **PR 3 — The Ledger's settleable balance.** *(ADR-031)* Delivers: `MERCHANT_AVAILABLE`, a
  per-merchant holding period, and a release job that moves cleared funds from pending to available.
  Decision: the job carries **no state table** — "has this been released" is answered by
  `uq_ledger_transactions_idempotency` on `funds-released:pi_x`, and "how much is left" by the signed
  sum of pending-account lines, so a released payment sums to zero and the job is idempotent by
  arithmetic as well as by key. Refund reversals are re-pointed to reference the **payment**, not the
  refund, so a partial-refund's net is what gets released rather than the gross. Tradeoff:
  `availableMinor` may go **negative** (a merchant refunding after payout owes PayMesh the
  difference) — clamping at zero was rejected as a second copy of the truth that would disagree with
  the entries.

- **PR 4 — Settlement.** *(ADR-032)* Delivers: a scheduled job cuts a merchant's available balance
  into a batch, submits a payout to the simulator, and posts `BANK_CASH` only on the provider's
  signed callback. Decision: three journals, and a failure is a **new** reversal transaction, never
  an edit of the cut — `available → SETTLEMENT_IN_TRANSIT → BANK_CASH`, or `→ available` on a
  terminal failure. The release/refund interleave race ADR-031 left open on principle (no lock
  without a measured failure to justify it) is closed here with the same row-lock pattern as Refund's
  over-refund guard, because Settlement is what makes a wrong `available` figure get **paid out**.
  Tradeoff: no FX, no fee deduction (no fee schedule exists to net against), and a payout is
  confirmed all-or-nothing — partial settlement isn't modeled because there's no provider event to
  trace a partial outcome to.

- **PR 5 — Notification.** *(ADR-033)* Delivers: a merchant notification recorded per committed
  `payment.succeeded`/`payment.failed`/`refund.succeeded`, rendered from code templates, dispatched
  by a simulated sender on a timer. Decision: record-on-event, send-on-timer — sending stays out of
  the event transaction so a notification failure can never roll back the payment that triggered it.
  No `notification_templates` table (code, like Risk's rules) and no `delivery_attempts` table
  (counters on the row, like Webhook's choice). Tradeoff: because the simulated sender never fails,
  `FAILED` and `attempt_count > 0` are only reachable in tests today, not in production — the retry
  path is proven by injecting a throwing sender, not by anything that happens naturally.

- **PR 6 — Reporting.** *(ADR-034)* Delivers: `GET /api/v1/reports/payment-summary` and
  `.../settlements`, plus async CSV export. Decision: **one append-only fact table**
  (`report_facts`), aggregated on read — `source_event_id` is the primary key, so a redelivered
  event is a refused insert rather than a double-counted payment, avoiding the concurrency bugs a
  mutate-in-place row-per-payment design would need to get right under out-of-order delivery. `asOf`
  is the newest fact's `recorded_at`, never wall-clock "now" — a stalled relay shows up as an `asOf`
  that stops advancing, which is exactly the delayed-data signal the SDD requires. Tradeoff: exports
  live in a `TEXT` column (no object storage exists in this project) with no retention/expiry sweep,
  and there's no pre-aggregated rollup — deferred until a `GROUP BY` over one merchant's own facts is
  measurably slow, not before.

- **PR 7 — Audit.** *(ADR-035)* Delivers: an append-only `audit_events` log for privileged actions
  (merchant freezes, role grants, secret rotations), immutable by the same trigger pattern as
  `ledger_entries`. Decision: recorded **in-process, inside the acting transaction**, deliberately
  **not** as an event consumer — Audit's subjects (a freeze, a rotation) publish no domain event at
  all, verified in code before choosing the shape, so a shared `AuditRecorder` port is called from
  inside the same transaction that commits the privileged action. Before/after values and the caller
  IP are stored as SHA-256 hashes, never plaintext. Tradeoff: **a failure to record is a failure to
  act** — if the audit append throws, the privileged action rolls back with it. Correct for a
  security log, but a real, stated coupling: audit's uptime is now part of every privileged action's
  uptime.

**Phase 2 status: done.**

---

## Phase 3 — Microservices extraction (in progress)

Plan of record: `docs/phase-3-microservices-extraction-plan.md`, executed one PR at a time in table
order. Wave **3A** builds the infrastructure with the monolith still whole; **3B–3D** pull services
out one at a time in order of coupling (leaves first, the money path last); **3E** closes out.

### 3A — Foundations (built; still one deployable)

- **PR 1 — Kafka backbone.** *(ADR-036)* Delivers: KRaft Kafka in `docker-compose.yml`, the
  `EventEnvelope` wire contract, and `KafkaEventPublisher` — **with no caller**. Decision: topic is
  derived from the **aggregate type**, not the event-type's domain prefix (`SETTLEMENT_BATCH` emits
  both `settlement.batch_cut` and `payout.*`, and those are causally chained — splitting them across
  topics named after the event prefix would silently break ordering); partition key is the
  aggregate id, the same grain ADR-012 already established for callback ordering. The publish call
  **blocks** on the broker's acknowledgement — a relay that treated the buffered `send()` return as
  success would stamp `published_at` on an event a crash then discards. Tradeoff: from the moment
  something actually publishes (PR 2), the existing 25-attempt/~1-minute dead-letter budget (ADR-025)
  would treat a simple broker outage as poison and dead-letter a healthy backlog — explicitly flagged
  here as "the first thing ADR-037 has to decide," not fixed in this PR.

- **PR 2 — Dual-path relay.** *(ADR-037)* Delivers: the relay now publishes to Kafka **and**
  in-process, and one `@KafkaListener` feeds events back through the same `EventDispatcher` and
  `processed_events` inbox — an event is delivered twice, applied once. Decision: **two independent
  columns**, not one shared gate — `published_at` (in-process) and `kafka_published_at` (Kafka),
  because the first cut of this design gated one column on both sinks and had a real money-path bug:
  during a Kafka outage, in-process-done-but-Kafka-pending rows saturate the bounded claim batch and
  new events stop being claimed at all — a broker outage stalling the Ledger. The dead-letter budget
  from PR 1's concern is resolved: it governs the **in-process sink only**; the Kafka sink retries
  forever with no budget, since an outage is global and self-healing, not a poisoned event. Tradeoff:
  every event is delivered and applied-checked twice while both paths run — the accepted cost of
  running a safety net that makes every later extraction (PRs 6–14) reversible by a flag.

- **PR 3 — Schema-per-service.** *(ADR-038)* Delivers: the 46 tables move into ten schemas (nine
  services + `platform`) via `ALTER TABLE … SET SCHEMA`, keeping every trigger, index and FK intact;
  nine fenced `NOLOGIN` `*_svc` roles prove a service cannot read another's tables. Decision: the
  schema mapping follows the **code package**, not the plan document's prose table, where the two
  disagreed (three corrections recorded: `api_credentials` → `merchant`, `provider_callbacks` and
  `refund_callbacks` → `payment`). The pervasive `* → merchants` FK is **kept**, not dropped —
  dropping it here would remove a live integrity guard for a whole PR with nothing yet to replace it;
  PR 4 drops each FK in the same step that lands its replacement, so there's never an unguarded
  interval. Tradeoff: this is a deliberately **lean carve** — one Flyway history, one
  `outbox_events`/`processed_events`/`idempotency_records` set in `platform`, not split nine ways —
  each ponytail-marked for the extraction that actually needs it, plus one real deployment caveat
  (a separate migrator role needs an explicit `GRANT USAGE` this single-process carve doesn't need).

- **PR 4 — Merchant reference projection.** *(ADR-039)* Delivers: `merchant.registered/activated/
  suspended/closed` events from Merchant's own outbox; a `merchant_ref(merchant_id, status,
  updated_at)` read model in each of six consuming schemas, fed by one `MerchantRefProjector`
  through the inbox; the platform's `MerchantStatusGate` reads the projection instead of the
  `merchants` table — the last direct cross-service table read. Decision: per-schema copies, not one
  shared `merchant_ref` (a shared table is "a shared database wearing a lanyard" — it re-couples
  every consumer to one table). The gate gains a third outcome: **absent from the projection → 503
  `MERCHANT_NOT_YET_AVAILABLE`** (retryable), distinct from present-but-inactive → 403. Tradeoff:
  **suspension is now eventually consistent** — a real, stated partial walk-back of ADR-021's
  "no cache, ever" stance, accepted because a few extra seconds of trading after a suspension is a
  policy lag, not a money-integrity failure; every test that flips merchant status must now drive the
  relay before asserting against the gate.

- **PR 5 — API gateway.** *(ADR-040)* Delivers: a standalone `gateway/` Maven module (Spring Cloud
  Gateway Server WebMVC, port 8081) — edge JWT validation from the same shared secret, routing every
  prefix to the monolith, Redis-backed per-IP rate limiting on `/api/**`. Decision: the edge mirrors
  the monolith's public/authenticated split **exactly** (auth routes, merchant registration, HMAC
  callbacks and simulator routes pass through unauthenticated at the edge, or a provider retry would
  401 before its signature is ever checked); the rate limiter **fails open** on a Redis outage,
  because Redis here is a throwaway counter, not an authority, and the graceful-degradation rule says
  it may fail without corrupting payments. Tradeoff: three real pins recorded — the proxy hop forced
  to HTTP/1.1 (h2c to a plaintext backend gets `RST_STREAM`), Lettuce pinned to 6.3.2 (bucket4j 8.15
  predates Lettuce 7), and Spring Cloud's Boot-4.0-only compatibility check disabled for Boot 4.1 —
  each a specific, load-bearing workaround rather than a style choice.

### 3B — Extraction wave 1: the pilot and the leaves

- **PR 6 — Provider Simulator (the pilot).** *(ADR-041)* Delivers: the simulator as its own
  deployable (`provider-sim/`, port 8082) — same package (`com.paymesh.simulator`), same tests
  where they could move unchanged, own pom, own `simulator` schema reached as the fenced
  `simulator_svc` role ADR-038 minted for exactly this day, own Flyway history that *adopts*
  the schema's existing five tables (`baseline-on-migrate`) rather than re-creating them.
  Decision: the wire protocol does not change — outbound callbacks stay the HTTP-POST-signed-body
  ADR-017 already built (never Kafka; the simulator predates the Kafka backbone and was never
  wired to it), because rewiring the transport *and* moving the process in one PR would bundle an
  unrelated redesign of ADR-012's ordering/dedup contract into the pilot whose only job is proving
  the move itself is cheap. `ReconciliationConfiguration`'s `base-url` and
  `SubmitPayoutsService`'s payout URL were already real HTTP calls to the simulator's own port
  ("loopback" by coincidence, not by construction) — extraction changes one number in each
  (8080 → 8082), nothing else. Two backend tests that drove the simulator in-process
  (`SimulatorCallbackDeliveryIntegrationTest`, `ReconciliationIntegrationTest`) could not survive
  as single-JVM tests once the classpaths split; both were adapted rather than deleted, with a
  WireMock stub standing in for whichever side moved out of reach — the same substitution the
  gateway's own tests already made for the monolith (ADR-040). Tradeoff: rollback is no longer a
  flag — it's re-pointing the gateway's `provider-sim-uri` at a monolith still carrying the old
  code, which git history preserves but which this PR does not leave running side-by-side; the
  dual-path *relay* (ADR-037) was never in this module's critical path to begin with, since the
  simulator never spoke Kafka.

- **PR 7 — Webhook.** *(ADR-042)* Delivers: the first *merchant-facing* leaf out of the process
  (`webhook/`, port 8083) — same package (`com.paymesh.webhook`), same tests where they could move
  unchanged, own pom, own `webhook` schema reached as the fenced `webhook_svc` role, own Flyway
  history that *adopts* the schema's existing three tables plus its `merchant_ref` copy
  (`baseline-on-migrate`) and then *creates*, fresh, the three platform tables (`processed_events`,
  `idempotency_records`, `outbox_events`) this deployable now needs its own copy of — ADR-038 kept
  those three in one shared `platform` schema specifically until "3B+", and this is that split, for
  webhook alone. Decision: the copied `shared.*` subtree (~30 classes across `api`, `outbox`,
  `idempotency`, `security`, `tenant`) keeps its original package names unchanged, rather than being
  renamed the way the pilot renamed its one shared class — rewriting ~30 imports for no behavior
  change would be exactly the busywork the recipe exists to avoid, and the duplication is the
  accepted, stated cost until `shared/` becomes a real library (PR 16). Security is JWT-only at this
  boundary (no `ApiKeyAuthenticationFilter`): minting a JWT from an `ApiKey` needs `api_credentials`,
  which is in the `merchant` schema and out of `webhook_svc`'s reach until Merchant is extracted (PR
  11), so a raw `ApiKey` presented directly to webhook is out of scope for this PR — the gateway
  fronts it. Auditing a secret rotation could no longer be an in-process `AuditRecorder.record(...)`
  call (`audit_events` is in the `engagement` schema now): `RotateWebhookSecretService` appends a
  `webhook.secret_rotated.audited` event to webhook's own outbox in the same transaction as the
  rotation instead, and one new monolith-side handler
  (`RecordWebhookSecretRotationAuditHandler`, wired into `AuditConfiguration`) turns it back into the
  same `audit_events` row the in-process call used to write — PR 8's mechanism (ADR-043), pulled
  forward one action early. Webhook is a genuinely independent second consumer of the event stream
  now: its own Kafka consumer group (`paymesh-webhook`, not the monolith's `paymesh-monolith`), its
  own `MerchantRefStore` copy trimmed from the monolith's six-schema fan-out to just `webhook` (the
  monolith's own copy drops `webhook` from its list in the same PR, for the same fencing reason).
  `ModuleBoundaryTest`'s webhook allowances were removed rather than left vacuous, the same call
  ADR-041 made for the simulator's. Gateway: `RoutesConfiguration` gained a `webhookRoutes` bean for
  `/api/v1/webhook-endpoints/**` → `webhook-uri` (default `:8083`) — the one route in that class where
  bean ordering is load-bearing rather than incidental (`/api/v1/webhook-endpoints/**` is a *subset*
  of `apiRoutes`' `/api/**`, unlike every other pair of predicates there), pinned with an explicit
  `@Order` and proven by a dedicated WireMock-backed routing test rather than left to bean-declaration
  luck. Tests: `WebhookIntegrationTest`/`WebhookEndpointPersistenceTest` moved to the module against a
  real PostgreSQL, their merchant fixtures replaced with a bare `MerchantId.generate()` (the FK to
  `merchants` was already dropped in V39, so nothing needs a real merchant row); the rotation test now
  asserts a `webhook.secret_rotated.audited` row in webhook's own `outbox_events`, not an `audit_events`
  row it can no longer write; a new monolith test proves the consumer half end to end. Tradeoff: the
  `shared` subtree is now duplicated between `backend` and `webhook` until PR 16 makes it a library —
  drift is possible and only a boundary test on each side catches it; webhook runs the full outbox
  relay for the one event type it produces today, the established pattern's fixed cost rather than a
  bespoke one-off publish.

- **PR 8 — Engagement (Notification + Reporting + Audit).** *(ADR-043, planned)* Goal: move the
  three read-side consumers out together. The one hard part: Audit isn't a pure event consumer today
  (ADR-035's subjects emit no domain event, only an in-process transactional call) — once the acting
  capability is in another service, that in-process call can't survive. Planned fix: each privileged
  action emits an `*.audited` event on its **own** outbox in the same local transaction as the action
  itself, and engagement consumes it through its inbox — moving the atomicity guarantee from "same DB
  transaction" to "same outbox transaction," which is strictly stronger than a synchronous cross-service
  call that could fail after the action already committed.

- **PR 9 — Risk.** *(ADR-044, planned)* Goal: the first *synchronous* extraction — payment's confirm
  needs a risk decision over the network, not a method call. Planned shape: Resilience4j around a
  gateway/mesh call, with ADR-030's fail-open/fail-closed-by-tier policy (specified for a Redis
  outage) extended to also cover "risk service unreachable." Verification target: a risk timeout
  applies the documented policy and records that it did — payments must neither hang nor silently
  allow through an unreachable risk check.

### 3C — Extraction wave 2: supporting core (planned)

- **PR 10 — Identity.** *(ADR-045, planned)* Goal: authentication becomes its own service, since
  every other service will depend on validating its tokens. Planned shape: token *issuance* moves
  here; token *validation* stays distributed (the gateway and each service verify independently via
  public key/introspection, never trusting a caller's say-so) — the same defense-in-depth principle
  ADR-040 already applies at the edge.

- **PR 11 — Merchant.** *(ADR-046, planned)* Goal: close the loop ADR-039 opened — the service that
  actually owns merchant status becomes the sole emitter every `merchant_ref` projection consumes,
  instead of the monolith emitting on its behalf. Its privileged actions (freeze/activate/close) emit
  the `*.audited` event PR 8 defined.

- **PR 12 — Settlement.** *(ADR-047, planned)* Goal: settlement runs standalone, reading the
  (still-monolith) Ledger's available balance **across the wire** rather than in-process. Planned
  shape: the batch-net-equals-items invariant stays a deferred trigger entirely inside the settlement
  schema (it never crossed a service boundary, so extraction doesn't touch it); a forced final-payout
  failure posts its reversal via a Ledger API call instead of a local method.

### 3D — Extraction wave 3: the money path (planned)

- **PR 13 — Payment (Order + Payment + Customer + Refund).** *(ADR-048, planned)* Goal: the
  synchronous money path leaves as **one** service, kept together because these four share the
  tightest transactional coupling in the system and a network hop between order and payment would be
  a real regression. Planned shape: confirm calls Risk (PR 9) synchronously and calls the Ledger API
  to post money, consuming `ledger.transaction.posted` back; ADR-031's sharp already-released-balance
  edge becomes a network call instead of a local debit. Explicitly the point of no easy return: ships
  only after 3A–3C are stable under production-shaped load.

- **PR 14 — Ledger (last).** *(ADR-049, planned)* Goal: the financial source of truth becomes its
  own service **last and whole**, specifically because its invariants (the deferred debits=credits
  trigger, immutability) never leave its schema and extracting it after every caller is already
  stable is the only ordering that protects the governing invariant. Planned shape: posting becomes
  an idempotent API (`Idempotency-Key` per caller+action) plus a `ledger.transaction.posted` event;
  the release job keeps running here on its own timer. Why last, stated plainly in the plan: every
  earlier service can tolerate eventual consistency or a brief outage — the Ledger cannot tolerate a
  distributed transaction, and its invariants cannot be distributed.

### 3E — Close-out (planned)

- **PR 15 — Observability across the hops.** *(ADR-050, planned)* Goal: one trace follows a request
  across every service it touches, once the money path spans nine deployables instead of one.
  Planned shape: OpenTelemetry auto-instrumentation, trace/span propagation through the gateway,
  synchronous calls and Kafka headers; Prometheus + Grafana.

- **PR 16 — Decommission the monolith.** *(ADR-051, planned)* Goal: delete the scaffolding once
  every capability runs as its own service — the dual-path flag, the in-process `EventDispatcher`,
  the shared in-process lookup adapters, the now-empty monolith module itself. `shared/` survives
  only as versioned libraries (the envelope, id value objects, security primitives) each service
  depends on explicitly rather than by being in the same process.

---

## Where we are now

**On `main`.** Phase 1 and Phase 2 are complete. Phase 3 wave 3A (PR 1–5) is merged — Kafka
backbone, dual-path relay, schema-per-service, merchant reference projection, API gateway. Phase 3B
PR 6 (the pilot) and PR 7 (Webhook) are both merged: the Provider Simulator (`provider-sim/`, port
8082, ADR-041) and Webhook (`webhook/`, port 8083, ADR-042) now run as their own deployables — four
independently-built Maven modules now exist (`backend/`, `gateway/`, `provider-sim/`, `webhook/`),
each with its own `pom.xml` and its own `./mvnw`. 42 ADRs. The monolith's migrations are still
V1–V39; `provider-sim`'s own Flyway history starts a separate V1 in the `simulator` schema (adopting
the tables the monolith's V13/V38 already created there); `webhook`'s own history adopts the
`webhook` schema's existing tables at V1 the same way, then creates its own copies of
`processed_events`/`idempotency_records`/`outbox_events` at V2 (new physical tables, not moved ones).
1325 backend tests + 122 webhook tests + 92 provider-sim tests + 11 gateway tests, all green — the
webhook capability's own tests moved to `webhook` with the package, one monolith-side test
(`WebhookMasterKeyStartupTest`) was retired in favour of webhook's own copy of that guard test, one
new monolith test (`RecordWebhookSecretRotationAuditHandlerIntegrationTest`) proves the audit-event
consumer half, and two gateway tests (`GatewayWebhookRouteTest`) prove the new route's precedence
over the `/api/**` catch-all.

**Next up: PR 8** — extract Engagement (Notification + Reporting + Audit) into its own deployable
(ADR-043, planned). The one hard part, named in the plan: Audit is not a pure event consumer today,
and PR 7 already pulled its replacement mechanism forward for webhook's one audited action
(`*.audited` event on the acting capability's own outbox, consumed through the new service's inbox) —
PR 8 generalizes that same mechanism to every remaining privileged action. Read
`docs/phase-3-microservices-extraction-plan.md` §"PR 8 — Engagement" before starting it.

Read `docs/phase-3-microservices-extraction-plan.md` §"PR 7 — Webhook" before starting it.
