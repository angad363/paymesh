# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

PayMesh is an educational Payment-as-a-Service backend. It processes no real money and claims no PCI/banking compliance. The point is to model a realistic payment platform (merchants, customers, orders, payments, providers, refunds, a double-entry ledger, balances, settlements, webhooks, risk, reporting) while learning Spring Boot, clean architecture, and event-driven / distributed-system patterns. Only the **merchant** capability exists so far; everything else is planned.

The full product/architecture vision lives in `docs/PayMesh_Payment_as_a_Service_Software_Design_Document.docx` — the Software Design Document (SDD). Read it before designing a new capability. It is a **target reference**: the codebase is at the very start of Phase 1 and implements almost none of it yet. The summary below captures what shapes day-to-day code decisions.

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

Public IDs are opaque, prefixed strings: `<prefix>_<uuid>` (ADR-003). Merchant uses `mrc_`; planned prefixes include `cus_`, `ord_`, `pi_`, `pay_`, `ref_`, `stl_`, `whe_`, `evt_`. IDs are value-object records (`MerchantId`) that validate the prefix + UUID in their compact constructor: `MerchantId.generate()` mints one, `MerchantId.from(String)` parses/validates. Do not expose sequential DB IDs.

### Persistence

There is no database yet. `InMemoryMerchantRepository` (an `ArrayList`) implements the `MerchantRepository` interface. When persistence lands it will be PostgreSQL + JPA behind the same `application`-layer interface, with a separate JPA entity (never the domain type) — integration tests are expected to use Testcontainers.

## The `docs/` folder is the source of truth for conventions

`docs/` contains detailed, authoritative convention specs — read the relevant one before designing a new capability or endpoint:

- `docs/PayMesh_..._Software_Design_Document.docx` — the SDD: full product vision, per-service designs, API/event/schema catalogs, workflows, and its own architecture decision records. The top-level reference for *what* to build and *why*.
- `docs/api/rest-api-conventions.md` — exhaustive HTTP/JSON contract (versioning, status codes, error shape, pagination, idempotency, money as integer minor units, timestamps as UTC `Instant`/ISO-8601, enum casing, etc.).
- `docs/development/java-coding-conventions.md` — layering, DI, immutability, exceptions, logging, testing, framework boundaries, no Lombok.
- `docs/decisions/ADR-*.md` — the repo's own numbered ADRs: `001` modular monolith, `002` package-by-feature, `003` opaque prefixed IDs. **Note:** the SDD (Appendix D) has a *separate* ADR list with the same numbers but different decisions (e.g. its ADR-001 is "money in minor units"). When citing an ADR, say which source you mean.
- `docs/domain/` and `docs/api/*-contract.md` — per-capability domain discovery and API contracts.

**These docs describe the target design and run ahead of the code.** The current merchant implementation intentionally diverges in places (e.g. the error body is a flat `{code, message, fieldErrors}` rather than the full RFC-7807 problem shape the doc specifies; validation failures currently return `400` where the doc prescribes `422`; the JSON id field is `id`, not `merchantId`). When extending existing code, match the **existing code**; when the two conflict and it matters, surface the divergence rather than silently picking one.

## Conventions for changes

- Branches: `feature/…`, `fix/…`, `test/…`, `docs/…`, `chore/…`. Commits: `type(scope): summary` (e.g. `feat(merchant): add merchant registration`). One focused change per PR. (See `CONTRIBUTING.md`.)
- Prefer records for immutable carriers (requests, responses, commands, value objects). Aggregates are mutable only through intent-revealing methods (`merchant.activate()`), never public setters. No Lombok.
- Test naming states behavior (`rejectsRegistrationWhenBusinessNameIsBlank`), not `test1`. Keep domain/application tests context-free (plain JUnit); reserve `@SpringBootTest`/`MockMvc` for the API layer.