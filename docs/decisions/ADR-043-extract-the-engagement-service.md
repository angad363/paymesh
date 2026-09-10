# ADR-043: Extract the engagement service — three leaves, one hard part

- Status: Accepted
- Date: 2026-09-09
- Scope: PayMesh Phase 3B, PR 8. New `engagement/` module (port 8084). Two new migrations in the
  monolith (`platform.outbox_events.merchant_id` becomes nullable, ADR-039's precedent for
  `merchant_ref`'s FK drop extended one step further) and one new migration each in the module's own
  Flyway history. Follows ADR-038 (schema-per-service), ADR-039 (merchant_ref projection), ADR-040
  (the gateway), ADR-041 (the extraction recipe), ADR-042 (the merchant-facing leaf, whose section 4
  this PR generalizes), and builds on ADR-033 (notification), ADR-034 (reporting), ADR-035 (audit).
  Plan of record: `docs/phase-3-microservices-extraction-plan.md` §3B PR 8.

## Context

Notification and Reporting are, and always have been, pure event consumers: everything they record
already rides the outbox, exactly the shape the Ledger's own consumer has. Moving them is the easy
half of this PR, mechanically identical to what ADR-041 and ADR-042 already proved — the package
moves verbatim, the shared platform subtree is copied not shared, the module gets its own schema,
its own Flyway history, its own Kafka consumer group.

Audit is not that shape, and the plan named this PR's hard part in advance: a merchant freeze, a
platform-role grant and a secret rotation emit no domain event at all. `AuditRecorder.record(...)`
is called **in-process, inside the acting transaction** (ADR-035) precisely because the guarantee it
gives — "a failure to record is a failure to act" — only holds when the audit write and the action
commit together. ADR-042 already met a smaller version of this problem: extracting webhook meant
`RotateWebhookSecretService` could no longer call `AuditRecorder` directly, and that PR's section 4
built the replacement mechanism — an outbox event, in the acting service's own transaction, consumed
back into `audit_events` by a handler in Audit — and said plainly that PR 8 would generalize it. This
is that generalization, for every remaining privileged action Audit records on another capability's
behalf: `ChangeMerchantStatusService` (Merchant) and `ManageUserAccessService` (Identity), both still
in the monolith.

## Decision

### 1. Notification and Reporting move verbatim; Audit moves whole, including its writer

`com.paymesh.notification`, `com.paymesh.reporting` and `com.paymesh.audit` relocate from
`backend/src/main/java` to `engagement/src/main/java`, unchanged — same classes, same packages, same
tests where they survive (§7). Audit's writer, `AuditRecorderAdapter` and its JPA store, moves with
it: Audit is now the sole process that can write `audit_events`, and every other capability reaches
it only through the events described in §5, never through an import.

The needed `com.paymesh.shared.*` subtree copies verbatim, package names unchanged — the same "copy,
do not share" call ADR-041 and ADR-042 made, scaled to a third module. Verified by grep before
copying, not assumed: `shared.api.ApiErrorResponse`; the JWT half of `shared.security.*`; the full
`shared.outbox.*` (consumer and producer halves, since Audit's new consumers need the inbox and the
two monolith producers this PR adds need nothing from engagement's copy — engagement never emits
across the wire); `shared.tenant.MerchantId` alone, **not** `MerchantStatusGate`/`MerchantRefStore`/
`MerchantRefProjector`/`MerchantStatusFilter` — none of Notification, Reporting or Audit ever
imported them, unlike webhook. `shared.idempotency.*` **does** come, unlike the trimmed set that
first draft assumed: `POST /api/v1/report-exports` is one of the monolith's registered idempotent
routes (`IdempotencyConfiguration.IDEMPOTENT_ROUTES`), so engagement's own copy of the filter carries
that one route — and only that one; `POST /internal/v1/audit-exports` was never on the monolith's
list, and this PR preserves that existing asymmetry rather than "fixing" it as a drive-by. And
`shared.audit.*` (the port `AuditRecorder`/`AuditEntry`/`ActorType`) moves with Audit rather than
being copied, because Audit is now the one process implementing it.

### 2. One cluster, the `engagement` schema, the role ADR-038 already minted; the inbox and
idempotency tables it did not

`engagement` connects to the same PostgreSQL cluster as `engagement_svc`, `search_path` narrowed to
`engagement, public`. The five existing tables — `notifications`, `report_facts`, `report_exports`,
`audit_events`, `audit_exports` — already live in the `engagement` schema (ADR-038 V38), including
`audit_events`'s immutability trigger, moved intact by `SET SCHEMA`. Flyway gets its own history,
`baseline-on-migrate: true` / `baseline-version: "1"`, adopting those five rather than re-creating
them — the ADR-041 pattern unchanged, `V1` byte-for-byte the live shape. `notifications`,
`report_facts` and `report_exports` already lost their `fk_*_merchant` constraints in the monolith's
own V39 (the merchant_ref projection replaced what they guarded, for services that read merchant
status — engagement never did); `audit_events` and `audit_exports` never had one, by design (V36: "an
audit row must survive the thing it describes"). `V2` creates this module's own copy of the three
platform tables ADR-038 left in the monolith's shared `platform` schema — `processed_events` (the
inbox, load-bearing the moment §5's three `*.audited` handlers exist), `idempotency_records` (the one
route from §1), `outbox_events` (present for parity with every other extracted service, though none
of the three capabilities here produce an event of their own today) — byte-for-byte webhook's `V2`
minus each table's FK to `merchants`, for the fencing reason V39 already stated.

### 3. Security is JWT-only, mirroring webhook; the merchant-status gate does not come, and that is
a real, accepted narrowing

Exactly ADR-042 section 3's posture: `oauth2ResourceServer().jwt()` against the same shared HS256
secret, the `AuthenticatedCaller` resolver, the JSON entry point/denied handler — no
`ApiKeyAuthenticationFilter`. The reporting and audit read/write surfaces keep their existing
controller-level checks (`requirePlatformAdmin()` for the two internal audit/notification routes,
merchant-scoped tenancy for the reports/exports routes) unchanged.

**What does not come is `MerchantStatusFilter`.** In the monolith, that filter guards every
non-`GET /api/v1/**` write by prefix, which means `POST /api/v1/report-exports` is *currently*
refused for a suspended or not-yet-projected merchant, as a side effect of a platform-wide filter —
not because Reporting's own code ever asked for that check. None of Notification, Reporting or Audit
imports `MerchantStatusGate` or `merchant_ref`, so carrying the filter forward would mean building
machinery this deployable has never needed on its own terms, for one route, to preserve an incidental
side effect. This PR does not: a report export can be requested by a merchant of any status once this
deployable is live, a genuine, stated narrowing of monolith behavior, not a silent one — the
replacement fixtures in `ReportExportControllerTest` (§7) say so directly, and this paragraph is the
other half of "surface the divergence" that the extraction workflow requires. If this narrowing turns
out to matter, the fix is `MerchantStatusFilter`'s own trimmed copy the way webhook carries one — not
reinstating the whole `shared.infrastructure.SharedConfiguration` fan-out.

### 4. The blocker this PR actually hit: `OutboxEvent.merchantId` was never allowed to be null, and
one of the two new audited events genuinely has no merchant

`ManageUserAccessService.audit(...)` was already called with a null `merchantId` for five of its
seven actions before this PR — `suspend`, `reactivate`, `close`, and both platform-role methods name
no tenant at all, and `AuditEntry.merchantId` has been nullable since V36 for exactly that reason
("a platform-role grant targets a user, not a merchant"). Turning that in-process call into an
outbox event (§5) meant the event itself had to carry the same fact, and `OutboxEvent`'s compact
constructor unconditionally rejected a null `MerchantId` — every event since ADR-010 has been about a
real tenant, because every capability that has ever emitted one owns a tenant-scoped aggregate.

Three shapes were available. **Inventing a sentinel "platform" `MerchantId`** was rejected first: it
would pass the `mrc_`-prefix format `CHECK` only by minting a UUID that names no real merchant, and
every future reader of `outbox_events.merchant_id` would have to know to treat that one value as
meaning "none" — the exact kind of ad hoc special case a format constraint exists to prevent.
**Keeping the in-process call for the platform-scoped cases only, switching just the two
merchant-scoped actions to the event**, was rejected as a worse asymmetry than the one it would
create: identical code (`ManageUserAccessService.audit`) would use two different mechanisms depending
on which branch called it, and the platform-scoped half would still be broken the moment Identity
itself is extracted (PR 10), deferring the real fix rather than making it. **Making `OutboxEvent`'s
`merchantId` genuinely nullable**, chosen: the smallest change that tells the truth. `MerchantId
merchantId` stays the type; the compact constructor's null check is removed; `OutboxEventJpaEntity
.merchantId` becomes a nullable column; `EventEnvelope.merchantId` becomes a nullable wire field;
`UnpublishedEvent.toEvent`/`EventEnvelope.toEvent` guard the `MerchantId.from(...)` call instead of
calling it unconditionally. One migration, `V40__allow_platform_scoped_outbox_events.sql` in the
monolith, drops the `NOT NULL` on `platform.outbox_events.merchant_id` — the FK to `merchants` (V7)
is **kept**, because a `FOREIGN KEY` does not fire on a `NULL` value, so a merchant-scoped event is
checked exactly as before and a platform-scoped one simply carries nothing to validate; the format
`CHECK` (V26) already tolerates `NULL` for the same STRICT-function reason `audit_events`'s own
`ck_audit_events_merchant_id_format` always has. Engagement's own `V2__engagement_platform_tables.sql`
defines its `outbox_events.merchant_id` nullable from the start, since it is a brand-new table with
no legacy `NOT NULL` to relax. This is the one place this PR's diff reaches outside the three
capabilities and the two producers — a platform primitive, widened by exactly one word, to say
something that was already true of the port it mirrors (`AuditEntry.merchantId`) and had no way to
say about the event that carries it.

### 5. Two privileged actions switch from an in-process audit call to an outbox event — the
generalized mechanism

Exactly ADR-042 section 4's shape, twice:

- **`ChangeMerchantStatusService.change(...)`** (Merchant, still in the monolith) replaces its
  `AuditRecorder.record(...)` call with `outbox.append(MerchantLifecycleEvents.auditedStatusChange
  (...))`, in the same transaction as the status change and the existing `merchant.<status>`
  lifecycle event. The new `merchant.status_changed.audited` event carries: the full action string
  (`merchant.activated`/`merchant.suspended`/`merchant.closed`, unchanged), the operator, `resource
  ("merchant", merchantId)`, the reason, and the plaintext `before`/`after` status names — the
  recorder on the other side still does the hashing, exactly once. `merchantId` is always real here:
  every status change is about a tenant. `AuditRecorder` is removed from the service's constructor
  and from `MerchantConfiguration`'s wiring entirely.
- **`ManageUserAccessService`**'s private `audit(...)` helper (the one call site all seven public
  methods route through) replaces its `AuditRecorder.record(...)` call with `outbox.append
  (UserAccessAuditEvents.of(...))`, in the same transaction as the action. Identity had no outbox
  producer before this PR; `OutboxWriter` is added to its constructor and wired in
  `IdentityConfiguration`. The new `identity.user_access.audited` event carries the dynamic action
  string, the operator, `resourceType="user"`/`resourceId=<target>`, and an optional reason (the role
  granted, when there is one) — no before/after and no IP, exactly as the in-process call never
  carried them. `merchantId` is the event's own field (§4): real for `access_granted`/
  `access_revoked`, `null` for the five platform-scoped actions.

The consumer half, in engagement: `RecordMerchantStatusChangeAuditHandler` and
`RecordUserAccessAuditHandler` join `RecordWebhookSecretRotationAuditHandler` (moved from the
monolith with the rest of Audit) as three `EventHandler` beans in `AuditConfiguration`. Each
reconstructs the `AuditEntry` from the plaintext payload and calls `AuditRecorder.record` — the
recorder hashes; the handler never does, for the same reason ADR-042 section 4 gives: hashing twice
would produce a different row than the in-process call used to write. Engagement's `KafkaEventListener`
already consumes every `.+-events` topic and dispatches to every registered handler, so three
consumers needed three `@Bean` methods, no listener wiring.

### 6. One Kafka consumer, its own group, carrying every domain-event handler these three
capabilities already had

Engagement gets one `@KafkaListener` on the `.+-events` pattern, its own consumer group
`paymesh-engagement`. It feeds `NotificationEventHandler` (three domain events), `ReportFactHandler`
(six), and the three audit consumers above — every `EventHandler` these capabilities registered
in-process before this PR, now behind this module's own inbox.

### 7. The gateway re-points two prefix groups

`RoutesConfiguration` gains `engagementRoutes` (`/api/v1/reports/**`, `/api/v1/report-exports/**` →
`engagement-uri`, default `:8084`, rate-limited like `webhookRoutes`) and `engagementInternalRoutes`
(`/internal/v1/notifications/**`, `/internal/v1/audit-events/**`, `/internal/v1/audit-exports/**` →
the same URI, unlimited like `internalCallbackRoutes`) — both `@Order(0)` for the same reason
`webhookRoutes` carries it: every one of these five prefixes is a *subset* of a broader route already
registered (`/api/**` or `/internal/**`), so without an explicit order a request could silently fall
through to `backend-uri`. The internal prefixes are **not** in the gateway's own permit list (unlike
the HMAC-authenticated provider/refund/payout callbacks), so they still require a valid, unexpired
token at the edge — the edge proves the token is real; `requirePlatformAdmin()` downstream still
decides whether it is the *right* token. `GatewayEngagementRouteTest` proves precedence over both
catch-alls the same way `GatewayWebhookRouteTest` does for webhook's one route.

### 8. Tests: move what survives, adapt the merchant fixtures, add the three consumer proofs

Domain and application unit tests move unchanged. The HTTP and Postgres-backed integration tests
(`ReportingIntegrationTest`, `NotificationIntegrationTest`, `ReportControllerTest`,
`ReportExportControllerTest`, `NotificationControllerTest`, `AuditPersistenceIntegrationTest`, the
audit controller tests) move to the module against a real PostgreSQL. Every fixture that used to save
a real `Merchant` through `MerchantRepository` (unavailable here — Merchant stays in the monolith)
is replaced with a bare `MerchantId.generate()`: `report_facts`/`report_exports`/`notifications`
carry no FK to `merchants` (§2), and this module carries no merchant-status gate to satisfy (§3), so
nothing needs a real row or a seeded `merchant_ref` projection — `ReportExportControllerTest` used to
seed one explicitly to avoid a 503 that can no longer happen. `AuditRecordingIntegrationTest` can no
longer drive `ChangeMerchantStatusService` directly (a different deployable's capability); it now
dispatches a `merchant.status_changed.audited` envelope through the same `EventDispatcher` every
other consumer shares, the identical substitution ADR-042 section 7 made for the webhook rotation
test. Two new tests, `RecordUserAccessAuditHandlerIntegrationTest` (both the null- and
real-`merchantId` shapes) and the equivalent for the merchant handler, prove each of the three audit
consumers writes the `audit_events` row from an envelope. In the monolith,
`LedgerConfigurationTest.registersEveryConsumerOfPaymentSucceeded` and
`RefundConfigurationTest.subscribesTheLedgerAndPaymentToRefundSucceeded` (renamed from
`...PaymentNotificationAndReportingTo...`) drop `notification.*`/`reporting.*` from their exhaustive
consumer lists, and `OutboxEventTest.rejectsANullMerchant` is replaced by
`permitsANullMerchantForAPlatformScopedEvent` (§4). `ModuleBoundaryTest`'s notification and audit
leaf-boundary tests are removed rather than left vacuous — the same call ADR-041 and ADR-042 made:
neither package exists in this module any more for `assertOnlyTheseImport` to walk, and what those
tests enforced (Audit reached only through the shared port, nothing importing Notification) is now a
compiler fact, not a runtime one.

## Consequences

- Engagement is a genuinely separate deployable: own pom, own port (8084), own Flyway history, own
  consumer group, no application secret of its own to guard (unlike webhook's master key). Five
  independently-built Maven modules now exist (`backend/`, `gateway/`, `provider-sim/`, `webhook/`,
  `engagement/`).
- Audit's invariant — "a failure to record is a failure to act" — is preserved across the process
  boundary by moving where the atomicity lives: from "same DB transaction as the action" (in-process)
  to "same outbox transaction as the action" (cross-process), which is strictly stronger than a
  synchronous audit API call that could fail after the action already committed. A merchant
  suspension in the still-monolith Merchant capability produces an immutable audit row in the
  separate engagement service; killing engagement does not roll back the suspension, it only delays
  the row until engagement's inbox catches up.
- **Accepted, stated costs:**
  1. The `shared` subtree is now duplicated across four modules (`backend`, `webhook`, `engagement`,
     and `provider-sim`'s trimmed one) until PR 16 makes it a library — drift is possible and only a
     `ModuleBoundaryTest`-shaped guard on each side catches it.
  2. `POST /api/v1/report-exports` is no longer refused for a suspended or unprojected merchant (§3)
     — a real behavior narrowing versus the monolith, reversible by giving engagement its own trimmed
     `MerchantStatusFilter` copy the way webhook's design already proves out, not yet built because
     nothing in these three capabilities has asked for it on its own terms.
  3. `OutboxEvent.merchantId` is nullable platform-wide now, not only inside engagement (§4) — every
     existing producer is unaffected (none has ever passed null), but every future reader of an
     `OutboxEvent`/`EventEnvelope` must now treat `merchantId()` as an `Optional`-shaped fact in
     practice, the same discipline `AuditEntry.merchantId()` already required of its callers.
  4. Engagement carries its own `outbox_events`/`OutboxRelay` for platform parity with every other
     extracted service, though none of its three capabilities produce an event of their own today —
     the established pattern's fixed cost, paid once, rather than a bespoke exception to it.
- Rollback is the gateway re-point (ADR-041's shape): point `engagement-uri` back at a monolith still
  carrying the code, plus the dual-path relay meaning no event is lost while engagement is absent. The
  monolith's two new outbox producers (`ChangeMerchantStatusService`, `ManageUserAccessService`) are
  harmless whether or not engagement is running — the events simply wait in the outbox.
- Postman gains a fourth base variable, `{{engagementBaseUrl}}` (`:8084`), for the Notification/
  Reporting/Audit folders, if a collection already carries them with request-level URLs to update.
