# PayMesh Phase 4 — System Validation, Load & Resilience Testing Plan

_Written 24 October 2026, after Phase 3 closed: the modular monolith is eleven
independently-deployable services around a Kafka event backbone (PR 1–15, ADR-001
through ADR-052), observability now follows one trace across every hop, and only
PR 16 — decommission the monolith — remains of the extraction. The code has been
verified a service at a time and through the Postman collection per PR. It has
**never been stood up whole and driven under load.** This plan does that._

This is the plan of record for proving the distributed system works as a system:
run all of it at once, drive the real money path end to end, measure latency and
throughput, find where one instance saturates, and — the part that matters most —
show the financial invariants still hold under load and partial failure. Read
`docs/project-status.md` for where the code actually is; read this for where the
testing is going.

**This is a target reference and runs ahead of the code, exactly as the SDD and
the Phase 3 plan do.** Nothing here is built. It also deliberately absorbs the one
chore Phase 3 deferred — a reproducible way to run the whole topology — because
nothing else in this phase is possible without it.

---

## The governing idea

Phase 4 is not a performance beauty contest. The number that matters is not
requests per second; it is whether **committed money movement is never lost,
duplicated, or made unauditable** when the system is busy and things are failing —
the same invariant that governed every earlier phase, now tested where it is
hardest to hold: under concurrency, back-pressure, and partial outage.

So every load exercise in this plan ends the same way: **the ledger is read back
and reconciled.** Debits equal credits; every `payment.succeeded` that the gateway
acknowledged is on the ledger exactly once; nothing the gateway rejected moved
money. A throughput chart with a balanced ledger underneath it is a result. A
throughput chart without that check is a vanity metric.

Three honest constraints shape the whole phase:

1. **There is no "run it all" button yet.** Services run via `./mvnw
   spring-boot:run` on the host; `docker-compose.yml` carries only infra (Kafka,
   Redis, the observability stack); Postgres is native with per-service schemas and
   fenced roles. Standing up eleven JVMs + the gateway by hand is the reason the
   system has never been load-tested. Building the "up" button is PR 1 and gates
   everything after it.

2. **A laptop cannot simulate a million concurrent users, and pretending it can
   produces lies.** One broker, one Postgres, one machine shared with the load
   generator does not scale to 10k real money-path users, let alone 1M. The useful
   local goal is to **find the knee** — the point where one instance saturates —
   and measure *what* saturates (CPU, the HikariCP pool, Kafka consumer lag, the
   outbox relay). The million-user question is a *distributed* exercise (load
   spread across boxes, services replicated on a cluster) and is scoped here as a
   target, not a laptop deliverable.

3. **Dual path is still live and the ledger release flag is off.** To load-test
   the actual service topology rather than the monolith's in-process path, traffic
   must route through the gateway with the service-path flags set on. A run against
   the wrong path measures the wrong system. PR 16 (decommission) removes this
   ambiguity; until then every run records which path it exercised.

**Reuse before building.** Four things already exist and this phase leans on them
instead of reinventing them: the **Postman/newman collection** (the functional
end-to-end check), the **observability stack** from PR 15 (Tempo + Prometheus +
Grafana — the telemetry sink for every load run), **actuator + Micrometer** (JVM,
HTTP, HikariCP and Kafka metrics already exported, no new instrumentation), and
the **ledger's own read API** (the reconciliation oracle). The genuinely new work
is orchestration, a load harness, two exporters, and fault injection.

---

## Prerequisite, stated once

**One reproducible command that brings the whole topology up**, lazy first then
proper, because the proper form is what load numbers require:

- **Lazy — a process orchestrator.** A `Procfile` driven by `overmind` (or
  `foreman`) that launches all eleven `spring-boot:run` processes + the gateway
  after `docker compose up -d` (infra + observability) and a provisioned native
  Postgres (all schemas, fenced roles, Flyway migrated). Good enough for functional
  end-to-end and a smoke trace; **not** good enough for honest load numbers,
  because every service shares the laptop's CPU with the load generator.

- **Proper — container images + compose.** `./mvnw spring-boot:build-image` per
  module (Spring Boot buildpacks, no Dockerfile to hand-write), and a compose
  profile that runs all services + a provisioned Postgres container alongside the
  existing infra. This is the real "run it all at once," the only form that gives
  trustworthy latency/throughput, and the on-ramp to a cluster for the scale-out
  work. It is also the containerization chore Phase 3 deferred — Phase 4 is where
  it finally pays for itself.

---

## The work, PR by PR

Each PR is independently useful and lands green before the next. The order is the
dependency order: you cannot load-test what you cannot start, and you cannot trust
a number you cannot see.

### PR 1 — The "up" button

**Goal:** one command stands up the whole topology, reproducibly.

- **Lazy form first** (`Procfile` + `overmind`, startup ordering: infra →
  observability → services → gateway), then the **buildpack images + compose
  profile** proper form.
- A one-shot **Postgres provisioning step** (schemas, fenced roles, grants, Flyway
  per service) so a fresh machine reaches a running system without manual SQL.
- **Verification:** the Postman collection passes end to end through the gateway,
  and one `authorize → … → ledger` request shows as a single connected trace in
  Grafana (the PR 15 acceptance, now against the *whole* system rather than a pair).

### PR 2 — End-to-end functional truth

**Goal:** the money path is correct across services before it is fast.

- Drive the existing **Postman/newman** collection through the gateway against the
  live topology, on the **service path** (flags set, path recorded).
- Add the **reconciliation oracle** as a reusable step: after a batch of payments,
  read the ledger and assert debits = credits and one entry per acknowledged
  payment. This becomes the tail of every later load run.
- **Verification:** collection green; ledger balances; refunds, settlement cut and
  holding-period release all land on the ledger as expected.

### PR 3 — The load harness

**Goal:** drive the money path under concurrency and see the knee.

- **k6** (scriptable in JS, native Prometheus output into the existing Grafana —
  the lazy best fit; Gatling or Locust are acceptable alternatives). One script
  for the authorize→capture→settle path through the gateway, with ramping virtual
  users.
- Record per stage: throughput (req/s), **p50/p95/p99** latency, error rate, and
  the saturating resource. Run the reconciliation oracle (PR 2) at the end of every
  run.
- Ramp to the **knee**, not to a target number. Expected first limits on one
  machine: Resilience4j breakers on `payment→risk`, HikariCP pool exhaustion,
  Kafka consumer lag, outbox-relay backlog.
- **Verification:** a documented knee per instance with the bottleneck named, and a
  balanced ledger at every load level.

### PR 4 — Observability that survives load

**Goal:** the telemetry is trustworthy and affordable while the system is busy.

- Drop trace **sampling** from `1.0` (fine for functional, ruinous under load) to a
  load profile (~`0.01–0.1`), env-selected so dev stays at `1.0`.
- Add a **kafka-exporter** (consumer lag — the single most important async-health
  signal under load) and a **postgres-exporter** (connections, locks, slow
  queries), both scraped by the existing Prometheus.
- Expand the minimal PR 15 dashboard into a **load dashboard**: RED per service,
  consumer lag, pool saturation, GC/heap.
- **Verification:** during a PR 3 run, every bottleneck the harness finds is
  visible and explained on the dashboard, not inferred.

### PR 5 — Correctness under partial failure (the real test)

**Goal:** prove the architecture's invariants hold when parts break mid-load.

- Inject faults during a load run: kill and restart a service, pause and resume
  Kafka, exhaust a connection pool, add latency to the `payment→risk` hop.
- Assert recovery: the **outbox/inbox** drains what was in flight, no event is lost
  or double-applied, and the **ledger reconciles** after the storm.
- **Verification:** for each injected fault, the ledger balances afterward and no
  acknowledged payment is missing or duplicated — the governing invariant, tested
  where distribution makes it hardest.

### PR 6 — Database design under real pressure

**Goal:** turn "is the schema good?" from opinion into measurement.

- Run a load, then analyze the **loaded** database (with `pg_stat_statements` on):
  top queries, missing/ineffective indexes, N+1 patterns, lock contention, pool
  sizing. (The Postgres insight tooling available to the agents does exactly this —
  health, workload-index and top-query analysis, `EXPLAIN` on hot queries.)
- Confirm the structural strengths already in place hold up: schema-per-service,
  fenced roles, ledger invariants enforced by triggers, the outbox unpublished
  index.
- **Verification:** a short findings note with any index/query fixes applied and
  re-measured.

### PR 7 — Scale-out (target, not a laptop deliverable)

**Goal:** answer the 10k / 1M question honestly — by distributing, not by pretending.

- The real path to high concurrency: services replicated on a cluster (K8s/Helm,
  SDD §22), Kafka at three brokers, Postgres with read replicas for the read-side,
  and load generated from **multiple boxes** (k6 Cloud or a distributed k6 run).
- Horizontal-scaling checks: do stateless services scale linearly behind the
  gateway; does consumer-group rebalancing keep lag bounded as consumers are added;
  does the ledger (the one capability whose invariants cannot be distributed)
  become the ceiling — and at what rate.
- **Explicitly out of scope for a single machine.** This PR is a target that
  presumes a real environment; it is documented here so the earlier PRs are built
  with it in mind, not so it is attempted on a laptop.

---

## What "good" looks like, per dimension

- **Runs at all:** one command, fresh machine, whole topology up, trace visible.
- **Correct:** Postman green and the ledger reconciles — at rest and after every
  load and every injected fault.
- **Fast enough:** p99 latency and throughput measured at the knee, with the
  bottleneck named — not a round number chased for its own sake.
- **Observable:** every bottleneck is explained on a dashboard, not guessed.
- **Scalable:** a defensible statement of what scales horizontally and where the
  ledger becomes the ceiling — proven on a cluster, reasoned about from the knee.

## Risks and how this plan answers them

- **Vanity numbers.** Answered by making the ledger-reconciliation check the tail
  of every run, and by measuring the knee rather than chasing 1M locally.
- **Measuring the wrong system.** Answered by recording the path (service vs
  in-process) on every run and preferring runs after PR 16's decommission.
- **Load telemetry that lies or costs too much.** Answered by PR 4 — sampling down,
  exporters in, dashboard before conclusions.
- **A laptop mistaken for a cluster.** Answered by scoping the million-user question
  as an explicit distributed target (PR 7), not a local deliverable.

## Working method, per PR

Unchanged from Phase 2 and Phase 3, because it worked: a short design note or ADR
before non-obvious work; one focused change per PR, landed green; verified live,
not on a report; the ledger reconciled as the final gate wherever money moved;
docs updated in the same PR when state changes. This phase begins only after
Phase 3's PR 16 (decommission the monolith) is merged, so the system under test is
the service topology and nothing else.
