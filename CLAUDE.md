# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

PayMesh is an educational Payment-as-a-Service backend. It processes no real money and claims no PCI DSS, banking, or regulatory compliance.

The project models a realistic payment platform: merchants, identity, customers, orders, payments, provider simulation, refunds, double-entry ledger, balances, settlements, webhooks, risk, reporting, audit, and event-driven service extraction.

## Current state

**Do not keep historical PR/status details in this file.**

`docs/project-status.md` is the authoritative pick-up-here document. Read it before assuming what is currently built, what is next, or which migration/ADR numbers are current.

For the current Phase 3 PR, read the matching section of:
`docs/phase-3-microservices-extraction-plan.md`

The SDD and Phase 3 plan are target/reference documents and may run ahead of the code. When they disagree with the current implementation, surface the divergence instead of silently rewriting the design.

## Source-of-truth documents

Read only the relevant document/section for the task; do not read entire documents by default.

- `docs/project-status.md` — current implementation state and next work.
- `docs/phase-3-microservices-extraction-plan.md` — Phase 3 plan of record.
- `docs/PayMesh_Payment_as_a_Service_Software_Design_Document.docx` — target product and architecture.
- `docs/api/rest-api-conventions.md` — HTTP/JSON/API contract rules.
- `docs/development/java-coding-conventions.md` — Java design and coding rules.
- `docs/architecture/package-structure.md` — package/module structure.
- `docs/decisions/ADR-*.md` — decisions and the reason behind them.
- `docs/domain/` and `docs/api/*-contract.md` — capability-specific domain/API contracts.

If an ADR or contract directly governs the requested change, read that specific document before editing.

## Non-negotiable architecture invariants

1. **Financial correctness**
   A request may fail or be retried, but committed money movement must never be lost, silently duplicated, or become unauditable.

2. **Ledger is the financial source of truth**
   Payment state is operational state. Financial truth lives in the double-entry ledger. Never edit/delete ledger history to correct a mistake; use a new reversal/correction transaction.

3. **Idempotency**
   Public writes, provider callbacks, and event consumers must be safe under retry and duplicate delivery. PostgreSQL is the durable authority; Redis is never the financial source of truth.

4. **Transactional outbox + inbox**
   Commit business state and the service's outbox event atomically. Consumers use `processed_events`/inbox semantics so duplicate events are safe. Delivery is at-least-once, not exactly-once.

5. **No distributed transactions**
   Each service owns its local transaction. Cross-service behavior is eventual, idempotent, and recoverable.

6. **Service owns its data**
   A service must not read another service's tables. Use an event-fed local read model or a synchronous API owned by the other service.

7. **Explicit state machines**
   Business actions use intent-revealing methods (`activate`, `confirm`, `capture`, etc.). Do not set domain status fields directly.

8. **Tenant isolation**
   Merchant-owned data is scoped by merchant. Resource IDs alone never authorize access.

9. **Non-authoritative infrastructure**
   Redis, reporting, notifications, and other read-side infrastructure may fail without corrupting financial state.

10. **AI is advisory**
    AI may explain, summarize, or analyze. It must not directly move money, post ledger entries, or approve financial actions.

## Architecture and Java conventions

### Package-by-feature

Use business modules under `com.paymesh`, not global technical packages.

```text
com.paymesh
├── merchant
├── customer
├── order
├── payment
├── ledger
└── shared
```

A capability normally follows:

```text
<feature>/
├── api/
├── application/
├── domain/
└── infrastructure/
```

Dependency direction:

```text
api → application → domain
infrastructure → application/domain contracts
```

Do not introduce global packages such as:

```text
com.paymesh.controller
com.paymesh.service
com.paymesh.repository
com.paymesh.dto
com.paymesh.util
```

### Spring wiring

Application/domain services and persistence adapters are ordinary classes, not component-scanned services.

- Prefer constructor injection.
- Wire application services/repositories explicitly through configuration.
- Use Spring annotations only at real framework boundaries such as controllers, configuration, and exception handlers.
- Do not add `@Service`, `@Repository`, `@Component`, or field `@Autowired` merely for convenience.
- No Lombok.

### Domain modeling

- Prefer immutable records for requests, responses, commands, events, and value objects.
- Keep domain aggregates responsible for their invariants.
- Prefer intent-revealing methods over public setters.
- Keep domain/application code HTTP-agnostic.
- Use business-specific exceptions; translate them to HTTP responses at the API boundary.
- Avoid generic `RuntimeException` for expected business failures.
- Do not return `null` collections; use empty collections.
- Use `Optional` for repository/query results where absence is expected, not as fields or parameters.

### Persistence

- PostgreSQL + Flyway is authoritative for durable state.
- Prefer database constraints for invariants where appropriate.
- Keep persistence entities separate from domain objects and map between them.
- Never bypass the service's schema ownership rules.
- Never make Redis the durable authority for financial/idempotency correctness.

### REST/API conventions

Use the API conventions document as the contract.

Core defaults include:

- Base path: `/api/v1`
- Resource-oriented plural nouns.
- Lowercase kebab-case URL segments.
- Lower camel case JSON fields.
- Descriptive path parameters (`{merchantId}`, not `{id}`).
- `POST` for creation/commands, `GET` for retrieval, `PATCH` for partial updates, `DELETE` only where deletion/deactivation is supported.
- Use domain-action endpoints such as `/confirm` or `/activate` when the operation is a business command.
- Preserve the existing API contract when extending existing code; do not silently "upgrade" old endpoints to a newer target shape.

## Testing and verification

- Use plain JUnit tests for domain/application logic where possible.
- Use Spring/HTTP tests at the API boundary.
- Integration tests use Testcontainers and require Docker.
- Prefer targeted tests during implementation.
- Run the relevant/broad suite once after the implementation stabilizes.
- Do not repeatedly run the entire suite after every small edit.
- When an invariant is important, prefer tests that would fail if the invariant were removed.

Commands from `backend/`:

```bash
./mvnw test
./mvnw test -Dtest=TestClassName
./mvnw test -Dtest=TestClassName#testMethod
./mvnw verify
./mvnw clean package
./mvnw spring-boot:run
```

Gateway commands from `gateway/`:

```bash
./mvnw test
./mvnw spring-boot:run
```

## Phase 3 execution workflow

Work **one PR at a time**, in the order specified by `project-status.md` / the Phase 3 plan.

For the current PR:

1. Read the current PR section and its relevant ADR.
2. Read `docs/project-status.md`.
3. Inspect only the affected capability and direct dependencies.
4. Produce a plan of **10 lines or fewer**.
5. Implement directly.
6. Run targeted tests.
7. Run the relevant full suite once when stable.
8. Run `/code-review` once after implementation if the user requested the normal PR workflow.
9. Update `docs/project-status.md` in the same PR when the implementation changes current project state.
10. Update README/Postman only when the change actually affects them.

Do not start the next PR before the current one is complete/merged.

## Agent efficiency rules

These rules are intentional.

### Direct work by default

- **Do not spawn subagents unless the user explicitly asks for them.**
- **Do not invoke `/office-hours` by default.**
- Use direct tool calls and direct edits for scoped repository work.
- Do not ask another agent to rediscover context already available locally.

### Read narrowly

- Read the assigned PR section first.
- Read `project-status.md`.
- Read the relevant ADR/contract/convention only.
- Use `git`, `grep`, `rg`, `find`, and targeted file reads before opening large files.
- Do not scan unrelated modules.
- Do not read the entire SDD just because the task mentions architecture.
- Do not reread files that have not changed and are already in context.

### Minimize unnecessary model work

- Do not restate the architecture when the ADR already defines it.
- Do not explore alternative designs unless the specified design is demonstrably incompatible with the code.
- Do not narrate options that will not be used.
- Keep implementation plans short.
- Prefer the smallest correct diff.
- Reuse existing helpers, ports, outbox/inbox patterns, ID constraints, configuration patterns, and test utilities before creating new abstractions.
- Do not add interfaces with only one implementation unless the architecture explicitly needs the seam.
- Do not add speculative configuration or scaffolding for future work.
- Stop when the stated acceptance criteria are satisfied.

### Context discipline

- Do not compact repeatedly just to keep a long session alive.
- When a PR is checkpointed and the conversation becomes large, prefer a fresh Claude Code session.
- Before starting a fresh session, ensure the state is recoverable from:
  - `docs/project-status.md`
  - `git status`
  - `git diff`
  - recent commit/PR history
  - targeted test results
- A fresh session should rediscover only the minimum state needed for the current PR.

### Token-discipline pattern

Apply the existing `/ponytail:ponytail` principle:

1. Reuse before inventing.
2. Fewest files and shortest working diff.
3. Read before editing, but do not reread unnecessarily.
4. Record deliberate corner-cuts and their upgrade path when the project convention requires it.
5. Do not over-engineer for hypothetical future services.

## Documentation maintenance

When a change makes the current state different:

- Update `docs/project-status.md` in the same PR.
- Update README run/topology instructions if they changed.
- Update Postman when the HTTP surface changes.
- Add/update an ADR when a non-obvious architectural tradeoff changes.
- Do not turn CLAUDE.md into a historical changelog. Keep it focused on durable instructions and pointers.

## Completion rule

The task is complete when the requested acceptance criteria are satisfied, the relevant tests pass, required project-state documentation is updated, and no unnecessary work remains.
