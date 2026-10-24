# PayMesh Phase 4 — Deploy, Validate & Load-Test the System

_Written 24 October 2026, after Phase 3 closed: the modular monolith is eleven
independently-deployable services around a Kafka event backbone (PR 1–15, ADR-001
through ADR-052), observability now follows one trace across every hop, and only
PR 16 — decommission the monolith — remains of the extraction. The code has been
verified a service at a time and through the Postman collection per PR. It has
**never been stood up whole, never run in the cloud, and never driven under load.**
This plan does all three, in that order._

This is the plan of record for proving the distributed system works as a system:
run all of it at once, **deploy it to AWS (for near-zero cost, while learning the
services)**, drive the real money path end to end, measure latency and throughput,
find where it saturates, and — the part that matters most — show the financial
invariants still hold under load and partial failure. Read
`docs/project-status.md` for where the code actually is; read this for where the
deployment and testing are going.

**This is a target reference and runs ahead of the code, exactly as the SDD and
the Phase 3 plan do.** Nothing here is built. It also deliberately absorbs the one
chore Phase 3 deferred — a reproducible way to run and ship the whole topology —
because nothing else in this phase is possible without it.

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

Four honest constraints shape the whole phase:

1. **There is no "run it all" button yet.** Services run via `./mvnw
   spring-boot:run` on the host; `docker-compose.yml` carries only infra (Kafka,
   Redis, the observability stack); Postgres is native with per-service schemas and
   fenced roles. Standing up eleven JVMs + the gateway by hand is the reason the
   system has never been load-tested. Building the "up" button is PR 1 and gates
   everything after it.

2. **The full system does not fit the AWS Free Tier, and pretending it does wastes
   money, not teaches it.** The free tier is one `t3.micro` (1 GB RAM) + one micro
   RDS; each Spring Boot service wants 300–500 MB, and the pieces that would make a
   cloud deploy "real" — managed Kafka (MSK), managed Kubernetes (EKS) — have no
   free tier at all. So the cloud work is split by goal: a **$0 learning path**
   (infrastructure-as-code against a local AWS emulator) and a **near-zero real
   path** (one right-sized box, run only during a session, hard budget cap). "Deploy
   the whole thing on AWS for literally $0" is not one of the options, and saying so
   up front is the honest part.

3. **Deploy is a different sizing problem from load testing.** A deploy-to-learn
   fits a tiny/free box and is about the *services and their wiring*. A
   deploy-to-load-test needs real compute and is about *throughput and failure*.
   One box cannot be both; the plan keeps them separate (the learning deploy is
   PR 3–4, the load-bearing cluster is the PR 9 target).

4. **A laptop — and a free-tier box — cannot simulate a million concurrent users,
   and pretending otherwise produces lies.** One broker, one Postgres, one small
   machine does not scale to 10k real money-path users, let alone 1M. The useful
   local goal is to **find the knee** — where one instance saturates — and measure
   *what* saturates. The million-user question is a *distributed* exercise (load
   spread across boxes, services replicated on a cluster) and is scoped as a target,
   not a laptop or free-tier deliverable.

**Dual path is still live.** To exercise the actual service topology rather than the
monolith's in-process path, traffic must route through the gateway with the
service-path flags set on; every run records which path it hit. PR 16 (decommission)
removes the ambiguity, and Phase 4 begins only after it merges.

**Reuse before building.** Four things already exist and this phase leans on them:
the **Postman/newman collection** (the functional end-to-end check), the
**observability stack** from PR 15 (Tempo + Prometheus + Grafana — the telemetry
sink and, self-hosted, the cloud one too), **actuator + Micrometer** (JVM, HTTP,
HikariCP and Kafka metrics already exported), and the **ledger's read API** (the
reconciliation oracle). The genuinely new work is orchestration, cloud IaC, a load
harness, two exporters, and fault injection.

---

## Prerequisite, stated once

**One reproducible command that brings the whole topology up**, lazy first then
proper, because the proper form is what both the cloud deploy and the load numbers
require:

- **Lazy — a process orchestrator.** A `Procfile` driven by `overmind` (or
  `foreman`) that launches all eleven `spring-boot:run` processes + the gateway
  after `docker compose up -d` (infra + observability) and a provisioned native
  Postgres. Good enough for functional end-to-end and a smoke trace; **not** good
  enough for load numbers (every service shares the laptop's CPU with the load
  generator) and not what ships to the cloud.

- **Proper — container images + compose.** `./mvnw spring-boot:build-image` per
  module (Spring Boot buildpacks, no Dockerfile to hand-write), and a compose
  profile that runs all services + a provisioned Postgres container. This is the
  real "run it all at once," the only form that gives trustworthy latency, **and the
  exact artifacts the cloud deploy pushes to a registry.** It is the
  containerization chore Phase 3 deferred — Phase 4 is where it pays for itself.

---

## Cloud deployment — AWS, honestly and for near-zero cost

The hard truth first (constraint 2 above): **the eleven-service topology will not
run in the AWS Free Tier.** So the cloud work takes two forms, by goal.

**The $0 learning path — IaC against a local emulator.** Write the whole deployment
as **Terraform** (or CDK) and apply it against **LocalStack**, which emulates AWS
(ECS, RDS, IAM, ALB, ECR, SSM, S3…) on your machine. You learn the real service
model, real IaC, and the real `aws` CLI — paying nothing and risking nothing. It
gives no public URL, but it is where the deployment is *designed and debugged*.

**The near-zero real path — one box, session-only, capped.** Reuse the same
Terraform to stand up a **single right-sized EC2 instance** (~16 GB, e.g.
`t3.xlarge`) running **ECS-on-EC2** (or docker-compose), **brought up only during a
session and terminated after.** At ~$0.15/hr a few hours of experimenting is under a
dollar — real cloud, real topology, cents not zero. **Self-host Kafka
(single-broker KRaft) and the observability stack on the instance** rather than
paying for MSK and managed Grafana. Guardrails (below) keep it safe.

What to **avoid**, because each is a standing bill: **MSK** (managed Kafka — self-host
instead), **EKS** (managed k8s control plane ~$73/mo — use ECS-on-EC2), **NAT
Gateway** (~$32/mo — use public subnets), and **idle Elastic IPs** (an unattached EIP
is billed).

**AWS services to learn, mapped to PayMesh:**

| PayMesh piece | AWS service | Cost |
|---|---|---|
| The service images | **ECR** (registry) | ~free (500 MB tier) |
| Running the containers | **ECS on EC2** (not EKS) | EC2 cost only |
| Compute | **EC2** | 750 hrs t3.micro free; larger box billed hourly |
| Postgres | **RDS Postgres** | 750 hrs micro free (12 mo) |
| Ingress to the gateway | **ALB** | 750 hrs free (12 mo) |
| Config / secrets | **SSM Parameter Store** / Secrets Manager | Parameter Store free |
| Networking + access | **VPC, subnets, security groups, IAM** | free |
| Logs / metrics / traces | **CloudWatch** (+ X-Ray 100k traces/mo) | small free tier |
| Kafka + observability | **self-host on the instance** | avoids MSK / managed cost |
| All of it, as code | **Terraform** (the highest-value skill here) | free |

**Cost guardrails, set before launching anything:** an **AWS Budget with a $1
alert** + free-tier usage alerts on; **terminate (not stop)** instances when done
so nothing lingers; no NAT Gateway, no idle EIP, no MSK/EKS. Free-tier terms are
12 months and change over time — **verify the current terms** for your account and
region rather than trusting a table.

---

## The work, PR by PR

Each PR is independently useful and lands green before the next. The order is the
dependency order: you cannot ship what you cannot start, you cannot trust a number
you cannot see, and deploy comes before load.

### PR 1 — The "up" button

**Goal:** one command stands up the whole topology, reproducibly.

- **Lazy form first** (`Procfile` + `overmind`; startup order: infra →
  observability → services → gateway), then the **buildpack images + compose
  profile** proper form (the artifacts PR 3–4 ship to the cloud).
- A one-shot **Postgres provisioning step** (schemas, fenced roles, grants, Flyway
  per service) so a fresh machine reaches a running system without manual SQL.
- **Verification:** the Postman collection passes end to end through the gateway,
  and one `authorize → … → ledger` request shows as a single connected trace in
  Grafana (the PR 15 acceptance, now against the *whole* system).

### PR 2 — End-to-end functional truth

**Goal:** the money path is correct across services before it is fast or remote.

- Drive the existing **Postman/newman** collection through the gateway on the
  **service path** (flags set, path recorded).
- Add the **reconciliation oracle** as a reusable step: after a batch of payments,
  read the ledger and assert debits = credits and one entry per acknowledged
  payment. This becomes the tail of every later load run.
- **Verification:** collection green; ledger balances; refunds, settlement cut and
  holding-period release all land on the ledger as expected.

### PR 3 — Infrastructure as code, on LocalStack ($0)

**Goal:** learn AWS and author the whole deployment without spending a cent.

- **Terraform** for the target topology: VPC + subnets + security groups, IAM
  roles, **ECR** repos, an **ECS-on-EC2** service set, **RDS Postgres**, an **ALB**
  to the gateway, **SSM Parameter Store** for config, CloudWatch log groups.
- Apply it against **LocalStack**; iterate until `terraform apply`/`destroy` is
  clean and the emulated stack wires up.
- **Verification:** `terraform plan` is stable and `apply` succeeds on LocalStack;
  the IaC is reviewed and ready to point at real AWS with only endpoint/credentials
  changed.

### PR 4 — Real AWS deploy, session-only and cost-capped

**Goal:** the real topology running on real AWS, for cents, with the brakes on.

- **Guardrails first:** AWS Budget + alerts, free-tier usage alerts, no
  NAT/EIP/MSK/EKS.
- Push the PR 1 **buildpack images to ECR**; `terraform apply` the PR 3 stack to a
  single right-sized EC2 instance running ECS-on-EC2 (**Kafka + observability
  self-hosted on the box**), RDS Postgres, ALB to the gateway.
- Run the Postman collection + reconciliation oracle **against the cloud URL**, then
  `terraform destroy` / terminate.
- **Verification:** the collection passes through the ALB against the cloud deploy,
  the ledger reconciles, and the account bill after teardown is under a dollar.

### PR 5 — The load harness

**Goal:** drive the money path under concurrency and see the knee.

- **k6** (JS scripts, native Prometheus output into the existing Grafana — the lazy
  best fit; Gatling or Locust acceptable). One script for the
  authorize→capture→settle path through the gateway, ramping virtual users.
- Record per stage: throughput, **p50/p95/p99** latency, error rate, the saturating
  resource. Run the reconciliation oracle at the end of every run.
- Ramp to the **knee**, not to a round number. Expected first limits: Resilience4j
  breakers on `payment→risk`, HikariCP pool exhaustion, Kafka consumer lag,
  outbox-relay backlog.
- **Verification:** a documented knee with the bottleneck named, and a balanced
  ledger at every load level.

### PR 6 — Observability that survives load

**Goal:** the telemetry is trustworthy and affordable while the system is busy.

- Drop trace **sampling** from `1.0` (fine for functional, ruinous under load) to a
  load profile (~`0.01–0.1`), env-selected so dev stays at `1.0`.
- Add a **kafka-exporter** (consumer lag — the key async-health signal) and a
  **postgres-exporter** (connections, locks, slow queries), scraped by the existing
  Prometheus.
- Expand the minimal PR 15 dashboard into a **load dashboard**: RED per service,
  consumer lag, pool saturation, GC/heap.
- **Verification:** during a PR 5 run, every bottleneck the harness finds is visible
  and explained on the dashboard, not inferred.

### PR 7 — Correctness under partial failure (the real test)

**Goal:** prove the invariants hold when parts break mid-load.

- Inject faults during a load run: kill and restart a service, pause and resume
  Kafka, exhaust a connection pool, add latency to the `payment→risk` hop.
- Assert recovery: the **outbox/inbox** drains what was in flight, no event is lost
  or double-applied, and the **ledger reconciles** after the storm.
- **Verification:** for each injected fault, the ledger balances afterward and no
  acknowledged payment is missing or duplicated.

### PR 8 — Database design under real pressure

**Goal:** turn "is the schema good?" from opinion into measurement.

- Run a load, then analyze the **loaded** database (with `pg_stat_statements` on):
  top queries, missing/ineffective indexes, N+1 patterns, lock contention, pool
  sizing. (The Postgres insight tooling available to the agents does exactly this.)
- Confirm the structural strengths hold up: schema-per-service, fenced roles, ledger
  invariants enforced by triggers, the outbox unpublished index.
- **Verification:** a short findings note with any index/query fixes applied and
  re-measured.

### PR 9 — Scale-out (target, not a laptop or free-tier deliverable)

**Goal:** answer the 10k / 1M question honestly — by distributing, not pretending.

- Services replicated on a cluster (K8s/Helm, SDD §22), Kafka at three brokers,
  Postgres with read replicas for the read side, and load generated from **multiple
  boxes** (k6 Cloud or distributed k6). Built on the PR 3–4 Terraform, scaled up.
- Horizontal-scaling checks: do stateless services scale linearly behind the ALB;
  does consumer-group rebalancing keep lag bounded as consumers are added; does the
  **ledger** (the one capability whose invariants cannot be distributed) become the
  ceiling — and at what rate.
- **Explicitly out of scope for a single machine, and it costs real money.**
  Documented here so the earlier PRs are built with it in mind, not so it is
  attempted for free.

---

## What "good" looks like, per dimension

- **Runs at all:** one command, fresh machine, whole topology up, trace visible.
- **Deployable:** the same Terraform brings the system up on LocalStack for $0 and
  on real AWS for cents, and tears down to a sub-dollar bill.
- **Correct:** Postman green and the ledger reconciles — at rest, after every load,
  and after every injected fault.
- **Fast enough:** p99 latency and throughput measured at the knee with the
  bottleneck named — not a round number chased for its own sake.
- **Observable:** every bottleneck is explained on a dashboard, not guessed.
- **Scalable:** a defensible statement of what scales horizontally and where the
  ledger becomes the ceiling — proven on a cluster, reasoned about from the knee.

## Risks and how this plan answers them

- **A surprise cloud bill.** Answered by designing on LocalStack for $0, running the
  real deploy session-only behind a budget alarm, and banning the standing-cost
  services (MSK, EKS, NAT Gateway, idle EIPs) by name.
- **Vanity numbers.** Answered by making ledger reconciliation the tail of every
  run, and measuring the knee rather than chasing 1M on a small box.
- **Measuring the wrong system.** Answered by recording the path (service vs
  in-process) on every run and running after PR 16's decommission.
- **Load telemetry that lies or costs too much.** Answered by PR 6 — sampling down,
  exporters in, dashboard before conclusions.
- **A small box mistaken for a cluster.** Answered by scoping the million-user
  question as an explicit distributed target (PR 9).

## Working method, per PR

Unchanged from Phase 2 and Phase 3, because it worked: a short design note or ADR
before non-obvious work; one focused change per PR, landed green; verified live,
not on a report; the ledger reconciled as the final gate wherever money moved;
docs updated in the same PR when state changes. This phase begins only after Phase
3's PR 16 (decommission the monolith) is merged, so the system under test — and
under deploy — is the service topology and nothing else.
