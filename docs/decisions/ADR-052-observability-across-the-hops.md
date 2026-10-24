# ADR-052: Observability across the hops — one trace follows the money path across eleven deployables

- Status: Accepted (§Decided below: a=OTLP→Collector, b=auto-instrument only, c=per-service+auto, d=stack+minimal dashboard)
- Date: 2026-10-24
- Scope: PayMesh Phase 3E, PR 15. Cross-cutting, non-authoritative infrastructure (invariant 9)
  touching every deployable — `backend/`, `gateway/`, `provider-sim/`, `webhook/`, `engagement/`,
  `risk/`, `identity/`, `merchant/`, `settlement/`, `payment/`, `ledger/`. Adds distributed tracing,
  metrics, and trace-stamped logs so a single request is debuggable as it crosses services. Changes
  **no** money-path behaviour, adds **no** synchronous dependency on the money path, and must not fail a
  request when a collector or backend is down. Plan of record:
  `docs/phase-3-microservices-extraction-plan.md` §3E "PR 15 — Observability across the hops".

**Numbering and scope note (the plan is stale).** The plan labels this "ADR-050" and says the money
path spans "nine deployables." Both are out of date: ADR-050 is the ledger extraction and ADR-051 is
the holding-period projection (both merged). **This is ADR-052**, and after PR 14 the money path spans
**eleven** deployables (the gateway is one of them), not nine. The design below is sized for eleven.

## Context

With PR 14 merged the money path is eleven independently-deployed JVMs behind a gateway, wired by Kafka.
A single `POST /payments` now fans out across the gateway, payment, a synchronous risk check, the
provider simulator, and — asynchronously, through the transactional outbox and Kafka — webhook,
settlement and the ledger. When that flow misbehaves there is today no way to follow one request across
the hops: each service logs in its own silo with no shared correlation id, and the asynchronous steps
are causally invisible. The plan deferred observability to 3E on purpose — "you cannot usefully trace
hops that do not exist yet" — and now they all exist.

Five facts from the code shape the decisions below.

1. **Every module is Spring Boot 4.1.0 and already has `spring-boot-starter-actuator`.** SB 4.x ships
   Micrometer `Observation` and Micrometer Tracing with an OpenTelemetry bridge; actuator already
   exposes a metrics surface. The tracing machinery is a dependency-and-config problem, not a
   build-it-from-scratch one.

2. **The gateway is Spring Cloud Gateway Server WebMVC (not reactive).** It is a servlet app like the
   others, so one instrumentation approach covers the edge and the services — no separate reactive
   propagation story.

3. **Kafka is reached through one publisher and one listener per module, both already centralised.**
   `KafkaEventPublisher` sends via a `KafkaTemplate<String,String>`; `KafkaEventListener` receives via a
   single pattern `@KafkaListener`. Spring Kafka integrates with Micrometer `Observation`
   (`observationEnabled` on the template and the listener container), which injects and extracts the W3C
   `traceparent` in Kafka record **headers** with no change to the message body.

4. **The event body is a frozen, additive-only contract — trace context does NOT belong in it.**
   `EventEnvelope` (ADR-036) is a published contract parsed by every consumer; its compatibility rule is
   additive-only within a version. W3C `traceparent` belongs in Kafka **headers** (the transport's own
   slot), exactly as the partition key lives in the record key and not the body. The envelope is left
   untouched.

5. **The outbox decouples the producing request from the send, and that breaks the trace by design.**
   The business transaction writes the `outbox_events` row on the request thread; `OutboxRelay` publishes
   it later, on a *scheduler* thread, in a *separate* transaction. The `outbox_events` table has **no**
   column for trace context today. So auto-instrumentation alone will root the Kafka producer span at the
   relay's poll, not at the HTTP request that wrote the row — unless we deliberately carry the request's
   trace context on the outbox row and restore it in the relay. Whether to do that is the central open
   question (§Decision, b).

### Constraints that are not up for discussion

- **Invariant 9 — non-authoritative infrastructure.** Tracing/metrics/log export may fail without
  corrupting financial state. No exporter may sit on the money-path's synchronous critical path; a
  collector being down must never fail or delay a payment. Exports are async/batched and best-effort.
- **Invariant 1 & 10.** Observability is advisory. It never moves money, posts ledger entries, or gates
  a financial action.
- **Dual-path discipline.** The `shared/` subtree is copied into nine modules (`backend`, `webhook`,
  `engagement`, `risk`, `identity`, `merchant`, `settlement`, `payment`, `ledger`; `gateway` and
  `provider-sim` carry no `shared/`). Any change to shared code must be mirrored byte-identically into
  every copy.
- **Ponytail.** Prefer the OTel/Micrometer Spring Boot starter's auto-instrumentation to hand-rolled
  spans. The smallest change that produces one connected cross-service trace wins.

## Decision

The spine is settled; four genuine choices are left **open for the human** (§Open questions). The spine:

- Add Micrometer Tracing + the Micrometer OTLP/OpenTelemetry bridge (and a metrics registry) to every
  deployable, configured through `application.yaml` and the actuator — auto-instrumentation of the
  servlet edge, `RestClient`/HTTP hops, and the Kafka template+listener.
- Propagate W3C `traceparent` end to end: through the gateway, through the synchronous HTTP hops
  (payment→risk, settlement→ledger, etc.), and through Kafka **headers** on the outbox→relay→listener
  path. The `EventEnvelope` body is not touched.
- Stamp the trace id and span id on every log line, so a log line and a trace can be cross-referenced.
  Spring Boot 4.1's `LogCorrelationEnvironmentPostProcessor` does this automatically once the tracing
  bridge is on the classpath (it sets `logging.pattern.correlation` with `traceId`/`spanId`), so **no
  logback file or shared change is needed** — this is the "SB default covers it" branch decision (c)
  anticipated, confirmed at review (the empty fixed-width `[…]` block on a no-span log line is that
  pattern). A hand-written logback pattern is added only if the default is ever disabled.
- Exports are best-effort and off the critical path (invariant 9).

## Decided (the four open questions, resolved by the human 2026-10-24)

- **(a) = Option 1 — OTLP → Collector → Tempo/Prometheus/Grafana.** Each service exports OTLP to one
  OpenTelemetry Collector; the collector fans out to Grafana Tempo (traces) and Prometheus (metrics),
  viewed in Grafana. One export protocol, one central routing/sampling point.
- **(b) = Option 1 — auto-instrument the Kafka hop only.** `traceparent` rides Kafka headers; the
  producer span roots at the relay's send and the consumer links to it. **No** `trace_context` column is
  added to `outbox_events` and **no** code is added to the money-path write path (invariant 9). Known
  limitation, recorded: a relayed event does not link back to the originating HTTP request; Option 2
  (carry trace context on the outbox row) is the documented upgrade path if end-to-end linkage is wanted.
- **(c) = Option 1 — per-service dependencies + `application.yaml`, auto-instrumentation.** The **only**
  change to the `shared/` subtree is the logback trace-id pattern (mirrored byte-identically across the
  nine copies). No Java agent, no new shared auto-config class.
- **(d) = Option 1 — ship the docker-compose observability stack + a minimal money-path dashboard.**
  Collector + Tempo + Prometheus + Grafana under `docker-compose`, plus a deliberately thin dashboard (a
  RED panel or two + trace explore), so the connected trace is demonstrable and reproducible.

## Open questions (resolved above — recorded for the record)

**(a) Export path and backends.** How do spans and metrics leave each JVM, and where do they land?
  - *Option 1 (recommended):* OTLP from each service to **one OpenTelemetry Collector**, which fans out
    to **Grafana Tempo** (traces) and **Prometheus** (metrics), viewed in **Grafana**. One export
    protocol, one place to change routing/sampling, boring and standard.
  - *Option 2:* direct exporters, no collector — Micrometer OTLP registry + a direct trace exporter per
    service. Fewer moving parts to run; every service re-configures routing and there is no central
    sampling/buffering point.
  - *Option 3:* **Jaeger all-in-one** for traces + Prometheus scrape of actuator for metrics. Simplest
    trace viewer to stand up; two unrelated pipelines and no collector seam for later.

**(b) Trace context across the asynchronous outbox hop — the central decision.** Auto-instrumentation
  puts `traceparent` in Kafka headers, so the *consumer* always links to whatever produced the record.
  The question is what the producer span is rooted at:
  - *Option 1 (recommended for this PR):* **rely on auto-instrumentation only.** The producer span is
    rooted at the relay's send; the consumer links to it. The result is still one connected trace across
    services (gateway→service→kafka→consumer→ledger) — the relay is simply the visible producer boundary.
    Zero change to the money-path write path, no new column, honours invariant 9 strictly. Cost: a
    relayed event does **not** link back to the original HTTP request that wrote the outbox row.
  - *Option 2:* **carry trace context on the outbox row.** Add a nullable `trace_context` column to
    `outbox_events` (a Flyway migration in every module, dual-path), capture the current `traceparent`
    when the business transaction writes the row, and have `OutboxRelay` restore it as the parent before
    publishing — so the relayed event links all the way back to the producing request. A faithful
    end-to-end trace; costs a schema change ×9+ modules and new code on the money-path write path, which
    is precisely what invariant 9 counsels against adding for a non-authoritative feature.

**(c) Where instrumentation lives, and how much is auto vs explicit.**
  - *Option 1 (recommended):* **per-service dependencies + `application.yaml`, auto-instrumentation**,
    with the **only** shared-subtree change being the logback trace-id pattern. No Java agent, no new
    shared auto-config class. Smallest diff; honours ponytail.
  - *Option 2:* a **shared tracing auto-config** in the `shared/` subtree, copied byte-identically into
    all nine modules, centralising the observation/Kafka wiring. One canonical config; nine copies to
    keep in lockstep (dual-path tax).
  - *Option 3:* the **OpenTelemetry Java agent** attached at JVM launch per service. Zero application
    code; a Dockerfile/launch-flag change per deployable and less control over span naming/filtering.

**(d) Grafana dashboards + the docker-compose observability stack — in scope or deferred?** The
  verification ("one connected trace across services") needs *a trace viewer* regardless, so some backend
  is in scope. The question is how much.
  - *Option 1 (recommended):* ship the **docker-compose observability stack** (collector + trace backend
    + Prometheus + Grafana) plus a **minimal** money-path dashboard (a RED panel or two + trace explore),
    so the connected trace is demonstrable and the stack is reproducible. Keep dashboards deliberately
    thin.
  - *Option 2:* ship the stack + instrumentation only (traces queryable in the viewer); **defer** curated
    Grafana dashboards to a later chore to keep this PR small.

## Consequences

- When accepted, the chosen options are recorded here and the ADR moves to Accepted; the frozen spec
  (`_bmad-output/implementation-artifacts/spec-observability.md`) carries them into implementation.
- PR 15 is independent of the ledger cutover — it does **not** flip `paymesh.ledger.release.enabled`.
- If Option (b)=1 is chosen, a known limitation is recorded: relayed events root at the relay, not the
  producing request; Option (b)=2 is the documented upgrade path.
- Next: PR 16 — Decommission the monolith (ADR-053).
