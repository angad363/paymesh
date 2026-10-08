# ADR-044: Extract the risk service — the first synchronous hop

- Status: Accepted
- Date: 2026-10-08
- Scope: PayMesh Phase 3B, PR 9. New `risk/` module (port 8085), the sixth deployable. No new
  monolith migration (see §2); three migrations in the module's own Flyway history. Follows ADR-038
  (schema-per-service), ADR-040 (the gateway), ADR-041 (the extraction recipe), ADR-042/ADR-043 (the
  leaf extractions this reuses wholesale), and is the change ADR-008 (the consumer-owned port) and
  ADR-030 (Risk decides, Payment acts) were both written in anticipation of. Plan of record:
  `docs/phase-3-microservices-extraction-plan.md` §3B PR 9.

## Context

Every extraction so far moved an **asynchronous** consumer: the simulator, webhook, engagement. A
service that only reads the event stream can leave the process with no caller noticing — the relay
already delivers to it over Kafka, and a brief outage is a queue that drains later.

Risk is different and that is the entire point of sequencing it here. Payment's `confirm` asks Risk
a question and **waits for the answer** before it opens its transaction (ADR-030). Turning that
in-process method call into a network call is the first time a PayMesh money-path operation depends
on another service being reachable *right now*. ADR-030 saw this coming and said so twice — in its
own text and in `ConfirmPaymentIntentService`'s comment: the in-process evaluation fails **closed**
because "if it cannot run then the transaction is already lost," and *"a fail-open default would
matter if Risk were a network call to somewhere else, and if it ever becomes one, this line is the
one to revisit."* It has become one. This ADR is that revisit.

## Decision

### 1. The seam does not move; only the transport does

`RiskCheck` (Payment's consumer-owned port, ADR-008) and `ConfirmPaymentIntentService` are
unchanged. The in-process `RiskModuleCheck` is joined by a second implementation,
`RiskServiceHttpCheck`, and a property picks between them. ADR-008's one-implementation-interface
exception — taken "because the substitution is the actual roadmap, not a hypothetical" — is now
collected: the substitution happened, and the application layer did not notice. `EvaluateRiskService`,
the ruleset and the assessment repository are byte-for-byte the same classes on both sides of the
wire; extraction moved the decision's *location*, not the decision.

### 2. Dual path, and therefore no monolith migration

`paymesh.risk.mode` is `in-process` (default) or `http`. The in-process path — `RiskModuleCheck`,
`EvaluateRiskService`, `RiskConfiguration`, the whole `com.paymesh.risk.*` subtree — **stays wired
in the monolith**, compiling and working, so rolling back a bad risk-service deploy is a property
flip, not a redeploy of different code (the plan's "in-process risk stays until this is proven").

This is the one way PR 9 departs from the ADR-041/042/043 recipe, which *removed* each capability's
code from the monolith and its allowance from `ModuleBoundaryTest`. Here the monolith still imports
`com.paymesh.risk.*`, still runs as the all-schema role ADR-038 gave it, and still reaches the
`risk` schema for the in-process path — so there is nothing to drop and **no monolith migration this
PR**. The `payment → risk` boundary-test allowance stays, correctly, because the dependency is still
real in `in-process` mode. The fence (revoking the monolith's `risk` access, removing the in-process
code) is a later PR's work, once `http` is proven in a real topology.

### 3. Risk owns a synchronous endpoint, authenticated by a shared key

`POST /internal/v1/risk-evaluations` on the risk deployable runs the same `EvaluateRiskService` and
returns `{permitted, assessmentId}` — the exact `RiskCheck.Decision` shape, no matched rules, the
anti-oracle of ADR-030 preserved across the wire. It is `/internal/**`, never `/api/**`: no merchant
token reaches it and the gateway forwards it unrate-limited, the same treatment the provider
callbacks and engagement's internal routes get.

Authentication is a **shared key** (`X-PayMesh-Risk-Key`, constant-time compared in
`RiskEvaluationKeyFilter`), not JWT. Webhook and engagement carry a JWT stack because a merchant's
bearer token reaches them; this route's only caller is Payment's confirm, machine to machine. A full
JWT stack for one caller that will never hold a merchant token is dead weight — this is the minimal
shape `SimulatorApiKeyFilter` already proved (ADR-017/041). The key is `@NotBlank`, so a deployment
that forgets it fails at startup rather than accepting an empty header.

### 4. Velocity becomes event-fed

The in-process `PaymentModuleVelocityLookup` reached into Payment's `GetPaymentIntentService` — a
call the risk deployable cannot make. In its place, a `payment_intent_ref` read model
(`merchant_id, customer_id, payment_intent_id, created_at`), fed by a `PaymentCreatedProjector`
consuming `payment.created` on risk's own consumer group (`paymesh-risk`), and read by the same
`PaymentVelocityLookup` port. New in this PR, born schema-local to `risk`, so — unlike
`merchant_ref` — there is exactly one copy and nothing to collapse later.

**The accepted cost, stated:** this read model lags the confirm it judges by the relay + broker
delay, so a customer opening several checkouts within a few hundred milliseconds can be undercounted
where the in-process count (a synchronous read inside the same transaction) saw every prior row
immediately. A velocity feature already tolerates noise; the alternative — a synchronous call back
into Payment for a fraud heuristic — reintroduces exactly the coupling this extraction removes.
Marked `ponytail:` in the code; revisit only if a burst-fraud pattern is shown to depend on
sub-second ordering this lag would miss.

### 5. Resilience: the by-tier policy ADR-030 deferred, now defined

The confirm→risk call is wrapped in a Resilience4j **circuit breaker** (programmatic core API, wired
by hand — not the AOP starter, for the same reason this codebase hand-wires everything: ADR-010's
note that annotation proxying fails its `final` application services) plus a **socket read/connect
timeout**, so a hung risk service costs the confirm seconds, not the JDK's default patience. The
evaluation still runs *before* the transaction opens (ADR-030), so a slow risk call holds no row
lock and no JDBC connection while it waits.

ADR-030's fail policy for the Redis case was fail-**closed, uniformly** — and then Redis was never
built, so no by-tier policy was ever written. This ADR **defines** one for "risk service
unreachable" for the first time; it does not extend an existing one. A risk **outage** is not a risk
**refusal** — nobody looked at the payment at all — so refusing every payment on the platform
because one dependency is briefly unreachable trades a contained problem for an uncontained one. The
amount decides which failure is worse:

- **At or above `fallback-block-at-or-above-minor`** (default 50000, a placeholder proportionate
  figure, not a risk-modelled one): fail **closed** — `RiskUnavailableException` → **503**
  (retryable), distinct from a real block's **422**. A large payment going through unevaluated is
  the more expensive mistake.
- **Below it:** fail **open** — allow, with a null `assessmentId` (Risk never ran; inventing an id
  would claim evidence that does not exist). A confirm that refuses on every network blip for a
  payment this small is the more expensive mistake.

Either way the fallback **records that it fired** (`RiskFallbackRecorder`, before it returns or
throws): "does not silently allow" means the allow is on the record, not merely that it happened.

### 6. Not every failure is an outage

The failure the fallback exists for is *unavailability*: a timeout, a connection refusal, a 5xx from
Risk, or the breaker already open. These, and only these, reach the by-tier policy. Two other
failures are deliberately **not** absorbed as outages, because doing so would silently bypass risk on
every sub-threshold confirm for as long as a bug or a typo lasted:

- a **4xx** (a bad request, or a rejected key) — our side is misconfigured, not Risk down;
- a **2xx with no `permitted` field** — a contract violation.

Both become `RiskServiceProtocolException`, which the breaker `ignoreExceptions` (so a persistent
misconfiguration cannot trip it) and which propagates — the confirm fails loudly as a 500, the
signal that gets the key or the contract fixed, rather than a quiet indefinite risk bypass.

One Resilience4j trap worth recording because it made the breaker inert in the first cut: a
`COUNT_BASED` window of 10 with the default `minimumNumberOfCalls` of **100** can never reach the
minimum, so the failure rate is never evaluated and the breaker never opens —
`CallNotPermittedException`, the whole fast-fail path, is dead code. `minimumNumberOfCalls` is tied
to the window size so the breaker can decide once the window is full. A test now drives it to OPEN,
so the fast-fail path is exercised rather than asserted.

### 7. Gateway

`riskRoutes` (`@Order(0)`) forwards `/internal/v1/risk-evaluations/**` to `paymesh.gateway.risk-uri`
(`:8085`), unrate-limited. `@Order(0)` is load-bearing — the path is a subset of the `/internal/**`
catch-all — the same reason `webhookRoutes`/`engagementInternalRoutes` carry it, proven by
`GatewayRiskRouteTest`. The edge also permits this path unauthenticated (`SecurityConfiguration`):
the caller holds no bearer token, only the shared key the risk deployable checks itself — the same
posture the provider/payout callbacks already have, not engagement's audit routes (which a human
reads with a real token).

## Consequences

- The `shared` subtree is duplicated into a sixth module until PR 16 makes it a library — the
  established, stated cost of the recipe; drift is caught only by each side's boundary test.
- `payment_intent_ref`'s eventual-consistency lag (§4) is a real, bounded weakening of the velocity
  signal, accepted over re-coupling Payment and Risk.
- The 50000 fallback threshold is a judgement, not a model — there is no fraud data behind it. It is
  the one knob that decides whether a legitimate customer's mid-size confirm fails during a risk
  outage; it is config, so it moves without a deploy.
- Running both paths means the monolith keeps carrying risk's code and schema access until a later
  PR fences it. The duplication between `backend`'s `com.paymesh.risk.*` and the `risk/` module is
  the price of a property-flip rollback.
