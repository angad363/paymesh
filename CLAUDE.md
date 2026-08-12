# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

PayMesh is an educational Payment-as-a-Service backend. It processes no real money and claims no PCI/banking compliance. The point is to model a realistic payment platform (merchants, customers, orders, payments, providers, refunds, a double-entry ledger, balances, settlements, webhooks, risk, reporting) while learning Spring Boot, clean architecture, and event-driven / distributed-system patterns.

**Phase 1 is complete.** Built and green: **Merchant**, **Identity & Access**, **Customer**, **Order**, **Payment**, the **Provider Simulator**, the **Ledger**, **Refund**, and **Reconciliation** — plus the platform work underneath them (PostgreSQL-backed idempotency, a transactional outbox with a relay, an in-process dispatcher and a `processed_events` inbox, and a retry budget with a dead letter). **Phase 2 has started**: **Webhook** is built (ADR-028) — merchant-facing endpoints, a signing secret that is derived rather than stored, an event-to-wire translator, and a scheduled dispatcher with its own retry budget. Risk, Settlement and Notification/Reporting are not. `docs/project-status.md` is the authoritative pick-up-here document and is kept current; read it before assuming anything about what exists.

The full product/architecture vision lives in `docs/PayMesh_Payment_as_a_Service_Software_Design_Document.docx` — the Software Design Document (SDD). Read it before designing a new capability. It is a **target reference and it runs well ahead of the code**: Phase 1 is built, Phase 2 (§14, §17–§20) is not, and several Phase-1 sections are deliberately only partly implemented. `docs/project-status.md` §"What of the SDD is implemented" maps section by section what actually exists. The summary below captures what shapes day-to-day code decisions.

## Target architecture (where this is heading)

The end state is ~15 services around a Kafka event backbone, but the roadmap is deliberately **modular-monolith-first**: build one deployable with strict module boundaries, prove the API/event contracts, and only extract services later (low-coupling ones like webhook/notification/provider/risk/reporting first; the Ledger last). Build for those boundaries now even while everything is one process — that is the whole point of package-by-feature.

**The governing invariant:** a request may fail or be retried, but committed money movement must never be lost, silently duplicated, or become unauditable. Most rules below exist to protect it.

- **The Ledger is the financial source of truth**, not payment rows. It is double-entry: every transaction's debits equal its credits, amounts are positive integers in minor units with direction stored separately, entries are immutable, and corrections are new reversal transactions (never edits/deletes). A `SUCCEEDED` payment is operational state; the balance only becomes real once the ledger posts.
- **Idempotency everywhere it matters.** Public writes, provider callbacks, and event consumers must be safe to retry. Durable idempotency scope is `merchant + endpoint/action + Idempotency-Key`, stored in **PostgreSQL** (Redis is only an accelerator). Same key + different body → `409`. Merchant registration already models the spirit of this via `existsByEmail`.
- **Transactional outbox + inbox.** A service commits its state change and an `outbox_events` row in the *same* transaction; a relay publishes to Kafka; consumers insert into a `processed_events` (inbox) table so duplicate delivery is a safe no-op. Delivery is at-least-once, never exactly-once.
- **Explicit state machines.** Callers request actions (`confirm`, `capture`, `activate`); they never set a status field directly. This is why domain aggregates expose intent methods, not setters.
- **Tenant isolation.** Every merchant-owned table carries `merchant_id` and every query scopes by it. An object ID never authorizes access on its own; cross-tenant access returns `404`/`403` without leaking existence.
- **Graceful degradation & non-authoritative caches.** Redis, notifications, and reporting may fail without corrupting payments. Reporting/read models are eventually consistent by design.
- **AI is advisory only.** The planned AI operations service can explain and summarize but must never post a ledger entry, move money, or approve a refund.

Money is always integer **minor units** + explicit currency; timestamps are UTC `Instant`/ISO-8601; enum values are `UPPER_SNAKE_CASE`; IDs are opaque prefixed strings (see below). Target stack: Java 21, Spring Boot, PostgreSQL + Flyway/Liquibase, Redis, Kafka (KRaft), Spring Security (JWT/OAuth2/OIDC + API keys + HMAC webhooks), Resilience4j, OpenTelemetry/Prometheus/Grafana/Loki/Tempo, Docker/Kubernetes/Helm/Terraform, Testcontainers.

## Commands

All commands run from `backend/` (the Maven project root). Use the wrapper `./mvnw`.

```bash
cd backend
./mvnw test                                            # run all tests
./mvnw test -Dtest=MerchantTest                        # single test class
./mvnw test -Dtest=MerchantTest#registersMerchant      # single test method
./mvnw spring-boot:run                                 # run the app (port 8080)
./mvnw clean package                                   # build the jar
./mvnw verify                                          # full build + tests
```

- **Java 21**, **Spring Boot 4.1.0**, Maven. Note Boot 4 specifics: the web starter is `spring-boot-starter-webmvc` (not `-web`), and Jackson is v3 — its `ObjectMapper` is `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson`.
- Health/info actuator endpoints are exposed at `/actuator/health` and `/actuator/info`.

## Architecture

**Package-by-feature, not package-by-layer** (ADR-002). Each business capability owns a top-level package under `com.paymesh` and is internally split into four layers. Never introduce global technical packages like `com.paymesh.controller` / `.service` / `.repository` / `.dto`.

```
com.paymesh.merchant
├── api             HTTP boundary: controller, request/response records, @RestControllerAdvice
├── application     use-case services, commands, repository interfaces, business exceptions
├── domain          aggregates + value objects that protect invariants (framework-free)
└── infrastructure  config (bean wiring) + persistence adapters
```

The dependency direction is inward: `api → application → domain`, with `infrastructure` implementing `application` interfaces. `com.paymesh.shared` holds cross-cutting code.

### Two conventions that are easy to violate

1. **Beans are wired manually, not component-scanned.** Application/domain services (`RegisterMerchantService`, `GetMerchantService`) and repository adapters are plain `final` classes with **no** `@Service`/`@Component`/`@Repository`/`@Autowired`. They are instantiated as explicit `@Bean` methods in an infrastructure `@Configuration` class (see `MerchantConfiguration`). Only true framework components (controllers, `@RestControllerAdvice`, `@Configuration`) carry Spring annotations. When adding a service, add a `@Bean` method — don't annotate the class. This keeps the domain/application layers testable as ordinary Java. (See `docs/development/java-coding-conventions.md` §13.)

2. **The request/domain/response separation is enforced, not optional.** The flow is `RegisterMerchantRequest` (API record, holds `@NotBlank`/`@Size` boundary validation) → `RegisterMerchantCommand` (application record) → `Merchant` (domain, owns normalization + invariants) → `MerchantResponse` (API record, built via `from(...)`). Never reuse a request record as a domain or persistence type, and never return a domain/persistence object from a controller.

### Where each kind of logic lives

- **Boundary validation** (required, blank, length, format) → Bean Validation annotations on the request record, triggered by `@Valid`.
- **Domain invariants + normalization** → static factory methods on the aggregate (e.g. `Merchant.register(...)` trims/lowercases email, uppercases country/currency, enforces formats). These throw `IllegalArgumentException`.
- **Business-rule failures** → dedicated exceptions in the `application` package (`MerchantEmailAlreadyExistsException`, `MerchantNotFoundException`). Domain/application code must stay HTTP-agnostic — no `ResponseStatusException`, no status codes.
- **HTTP translation** → a per-feature `@RestControllerAdvice` (e.g. `MerchantExceptionHandler`) maps each exception to a status + `ApiErrorResponse`. Time is injected via a `Clock` bean so services are deterministic in tests.

### Identifiers

Public IDs are opaque, prefixed strings: `<prefix>_<uuid>` (ADR-003). In use: `mrc_` (merchant), `cus_`, `ord_`, `pi_` (payment intent), `ref_`, `evt_` (outbox event), `whe_` (webhook endpoint), `whv_` (webhook event), `whd_` (webhook delivery). Planned: `pay_`, `stl_`. IDs are value-object records (`MerchantId`) that validate the prefix + UUID in their compact constructor: `MerchantId.generate()` mints one, `MerchantId.from(String)` parses/validates. Do not expose sequential DB IDs.

### Persistence

**PostgreSQL + Flyway, twenty-nine migrations (V1–V29).** Every capability's `application` layer declares repository interfaces; `infrastructure/persistence/jpa` implements them with a **separate JPA entity, never the domain type**, and a mapper between the two. `ddl-auto=validate`, so a mapped column that drifts from its migration fails startup rather than surprising someone later.

Migrations are hand-authored and heavily commented — they are where several invariants actually live (deferred constraint triggers for debits-equal-credits, immutability triggers on ledger entries, composite tenant foreign keys, partial unique indexes). **Prefer a database constraint over an application check** where the choice exists; the application pre-check turns a violation into a readable 409/422, but the constraint is what makes it true.

Integration tests use Testcontainers and **need Docker running** — without it ~450 tests error on context startup rather than failing meaningfully. Run the full suite with `./mvnw test`.

### Scheduled jobs

Several capabilities own a timer (order expiry, abandoned checkout, payment and refund processing timeouts, the outbox relay, simulator callback dispatch, reconciliation). Two rules hold for all of them:

1. **The `@Scheduled` class contains no logic** — it calls one service method and logs the result. Every rule lives in a plain object taking an injected `Clock`, so tests drive it directly instead of booting a context and waiting for a tick.
2. **They are all off under the `dev` profile**, which is what the test suite runs on. A timer mutating rows underneath an assertion is a flake generator. The services are ordinary beans regardless, so tests call them directly.

## The `docs/` folder is the source of truth for conventions

`docs/` contains detailed, authoritative convention specs — read the relevant one before designing a new capability or endpoint:

- `docs/PayMesh_..._Software_Design_Document.docx` — the SDD: full product vision, per-service designs, API/event/schema catalogs, workflows, and its own architecture decision records. The top-level reference for *what* to build and *why*.
- `docs/api/rest-api-conventions.md` — exhaustive HTTP/JSON contract (versioning, status codes, error shape, pagination, idempotency, money as integer minor units, timestamps as UTC `Instant`/ISO-8601, enum casing, etc.).
- `docs/development/java-coding-conventions.md` — layering, DI, immutability, exceptions, logging, testing, framework boundaries, no Lombok.
- `docs/decisions/ADR-*.md` — the repo's own numbered ADRs, **thirty-one of them and the best record of why anything looks the way it does.** `001` modular monolith, `002` package-by-feature, `003` opaque prefixed IDs; later ones carry the load-bearing money decisions (`012` callback dedup and ordering, `015` payment timeout, `016` in-process event dispatch, `018` the Ledger, `019` refunds, `025` the outbox dead letter, `026` reconciliation, `028` webhooks and the secret that is never stored, `029` identifier format constraints, `030` risk decides and payment acts, `031` the ledger releases its own funds). Read the relevant ADR before changing anything on the money path. **Note:** the SDD (Appendix D) has a *separate* ADR list with the same numbers but different decisions (e.g. its ADR-001 is "money in minor units"). When citing an ADR, say which source you mean.
- `docs/domain/` and `docs/api/*-contract.md` — per-capability domain discovery and API contracts.

**These docs describe the target design and run ahead of the code.** The current merchant implementation intentionally diverges in places (e.g. the error body is a flat `{code, message, fieldErrors}` rather than the full RFC-7807 problem shape the doc specifies; validation failures currently return `400` where the doc prescribes `422`; the JSON id field is `id`, not `merchantId`). When extending existing code, match the **existing code**; when the two conflict and it matters, surface the divergence rather than silently picking one.

## Conventions for changes

- Branches: `feature/…`, `fix/…`, `test/…`, `docs/…`, `chore/…`. Commits: `type(scope): summary` (e.g. `feat(merchant): add merchant registration`). One focused change per PR. (See `CONTRIBUTING.md`.)
- Prefer records for immutable carriers (requests, responses, commands, value objects). Aggregates are mutable only through intent-revealing methods (`merchant.activate()`), never public setters. No Lombok.
- Test naming states behavior (`rejectsRegistrationWhenBusinessNameIsBlank`), not `test1`. Keep domain/application tests context-free (plain JUnit); reserve `@SpringBootTest`/`MockMvc` for the API layer.