# ADR-038: Schema-per-service — nine service schemas in one cluster, still one process

- Status: Accepted
- Date: 2026-09-05
- Scope: PayMesh Phase 3A, PR 3. Migration V38. Follows ADR-036/037. Plan of
  record: `docs/phase-3-microservices-extraction-plan.md` §3A PR 3.

## Context

The monolith keeps all 46 tables in one `public` schema, one Flyway history, one
DB role (`paymesh_app` in dev; the container superuser under Testcontainers).
Phase 3B onward lifts a capability's packages into their own deployable, and the
recipe (plan §3B) assumes the capability's tables **already live in a named
schema it can carry out with it**. This PR carves that boundary — the last big
step that ships inside the still-single deployable — so every later extraction is
"lift the package and point it at its schema," not "split the database under
load."

Nothing is extracted here. The app still boots as one process, one datasource,
one Hibernate, and the whole suite still passes.

## Decision

### 1. Ten schemas: nine services plus `platform`

Each of the nine target services (plan §"Target service topology") gets a schema
named for it, and the three shared platform tables get a tenth:

| Schema | Tables |
|---|---|
| `identity` | `users`, `user_roles`, `refresh_tokens`, `security_events` |
| `merchant` | `merchants`, `merchant_status_history`, `api_credentials`, `kyc_submissions` |
| `payment` | `customers`, `customer_status_history`, `payment_method_tokens`, `orders`, `order_state_history`, `payment_intents`, `payment_attempts`, `payment_state_history`, `provider_callbacks`, `refunds`, `refund_state_history`, `refund_callbacks` |
| `ledger` | `ledger_accounts`, `ledger_entries`, `ledger_transactions` |
| `settlement` | `settlement_configs`, `settlement_batches`, `settlement_items`, `payouts`, `payout_callbacks` |
| `risk` | `risk_assessments`, `denylist_entries` |
| `simulator` | `provider_payments`, `provider_payouts`, `provider_refunds`, `provider_outbound_callbacks`, `provider_failure_profile` |
| `webhook` | `webhook_endpoints`, `webhook_events`, `webhook_deliveries` |
| `engagement` | `notifications`, `report_facts`, `report_exports`, `audit_events`, `audit_exports` |
| `platform` | `outbox_events`, `processed_events`, `idempotency_records` |

**The mapping follows the code package, not the plan's prose table**, and where
they disagree the code wins (per CLAUDE.md: match the existing code). Three
divergences from the plan §"The 46 tables" are deliberate and recorded here:

- **`api_credentials` → `merchant`, not `identity`.** `ApiCredentialJpaEntity`
  lives in `com.paymesh.merchant` (ADR-022, merchant API keys). At extraction the
  merchant package carries it, so its schema must be merchant's.
- **`provider_callbacks` → `payment`, not `simulator`.** It is PayMesh's record of
  callbacks it *received* (`com.paymesh.payment`), not the simulator's outbound
  log (`provider_outbound_callbacks`).
- **`refund_callbacks` → `payment`, not `settlement`.** It is Refund's own
  callback route (ADR-019), `com.paymesh.refund`.

The plan also lists tables that do not exist (`payout_attempts`,
`refund_attempts`, `refund_reservations` — all deliberately unbuilt); they are
omitted. All 46 real tables are assigned to exactly one schema.

`platform` is the **lean-carve** choice (see §5): the three shared tables stay
one physical copy each, in their own schema, rather than being split nine ways
now.

### 2. Move by `ALTER TABLE … SET SCHEMA`, keeping every constraint and trigger

V38 creates the schemas and relocates each table with `ALTER TABLE <t> SET SCHEMA
<s>`. This moves the table *and* its indexes, owned sequences, constraints and
triggers as one unit — nothing is dropped and recreated. In particular:

- **The ledger's deferred `debits = credits` trigger and the immutability
  triggers move intact with `ledger_*` into the `ledger` schema** — confirming
  the load-bearing claim that they are wholly inside one schema (plan §"the Ledger
  extracts last"). Nothing else may write ledger tables, and this PR is where that
  is now physically true.
- **Shared functions stay in `public`** (`is_prefixed_id`, the trigger
  functions). A CHECK or trigger binds its function by identity at creation, not
  by `search_path` at execution, so a moved table's `is_prefixed_id(...)` CHECK
  keeps calling `public.is_prefixed_id` with no change. Each service gets its own
  copy of these functions only when it is physically extracted (3B+); duplicating
  them now would be nine copies nothing uses yet.

### 3. Cross-schema FKs stay valid — the `* → merchants` FK is **kept**, not dropped

Postgres allows a foreign key to reference another schema **within one database**,
so every FK survives the move unchanged. The only boundary-crossing FK is the
pervasive `* → merchants(merchant_id)` on ~20 tables. The plan's PR 3 drops it;
this PR **keeps it**.

The reasoning is the governing invariant. Dropping it here would remove a live
money-path integrity guard for a whole PR, with nothing replacing it until PR 4
introduces the `merchant_ref` projection. Keeping it costs nothing while the
database is one cluster (the FK is still enforceable) and lets **PR 4 drop each FK
in the same step that lands its replacement** — never a window where a merchant
reference is unguarded. The format CHECK (`is_prefixed_id(merchant_id, 'mrc_')`)
is already per-row and needs no foreign table, so it is untouched. This is the
one place this PR consciously trades "no cross-schema FK left" (a plan target) for
"no unguarded interval on the money path" (the invariant).

### 4. Nine restricted roles prove the fence; the app keeps one spanning role

Isolation is a **grant**, not a hope: V38 creates nine `NOLOGIN` roles
(`identity_svc`, `merchant_svc`, …) and grants each `USAGE` on its own schema plus
DML on that schema's tables, and nothing else. `SchemaIsolationTest` then, for
each role, `SET ROLE`s to it and asserts a `SELECT` against another service's
table is refused (`permission denied for schema …`) while its own succeeds. That
test is the isolation guarantee made executable — and the artifact the extracted
services will adopt verbatim as their own connection role.

The **single-process app does not connect as these roles yet.** One Hibernate over
one datasource spans all ten schemas, so it connects as a role that can see them
all (`paymesh_app` in dev, the superuser under Testcontainers). Each service
adopts its restricted role when it gets its own datasource at extraction. Wiring
nine datasources now would be nine of everything for a process that is still one —
exactly the over-build this phase's token discipline forbids.

Role creation is wrapped so that a dev role lacking `CREATEROLE` skips it with a
notice rather than failing the migration: the roles are extraction-prep, not
needed for the single process to run. The schema creation and table moves are
**not** tolerant — if they fail the app cannot work, so failing loud is correct.
(Dev bootstrap: `GRANT CREATE ON DATABASE paymesh TO paymesh_app;` and, to
exercise the roles locally, `ALTER ROLE paymesh_app CREATEROLE;`. Testcontainers
runs as superuser and needs neither.)

One deployment caveat, out of scope to fix here because no such deployment exists
yet (`infrastructure/` is empty, SDD §27 not started): the grants above make each
schema readable by its *owner* and by its `*_svc` role. Under Testcontainers and
dev the migrating role and the app role are the same (superuser; `paymesh_app`,
which owns what it created), so the app sees every schema implicitly. A future
deployment that runs migrations as a *separate* migrator role from the runtime app
role must additionally `GRANT USAGE ON SCHEMA … TO <runtime role>` (or run the app
as one of the `*_svc` roles). That belongs to the deployment/IaC work, not to this
single-process carve.

### 5. Lean carve: Flyway history and the platform tables stay single, for now

Two pieces the plan lists under PR 3 are **deferred to each service's extraction**,
each marked with a `ponytail:` note where the code would otherwise grow:

- **One Flyway history, not nine.** History stays in `public.flyway_schema_history`
  and one `spring.flyway`. Nine Flyway beans buy nothing while one process owns all
  migrations; a service takes its own history (numbered from V1) when it takes its
  own deployable and repo. Migrations from V38 on **schema-qualify** their table
  names, since `public` no longer holds them.
- **One `outbox_events` / `processed_events` / `idempotency_records`, in
  `platform`.** Splitting them nine ways requires teaching the shared relay,
  dispatcher and idempotency filter which schema a row belongs to — routing that is
  rewritten and moved when each service takes its own outbox at extraction.
  Building it now is code with no caller. The tables move to `platform` so they are
  no longer in a service schema, and split per-service in 3B+.

The essential deliverable — every capability's tables in a schema it can carry out
of the process — is complete without either. This is the smallest carve that
unblocks 3B.

### 6. How unqualified entity names still resolve

Entities keep bare `@Table(name = "orders")` (no per-entity schema), and every
app connection sets `search_path` across all ten schemas plus `public` via
Hikari's `connection-init-sql`. Because **all 46 table names are globally unique**
(they shared one schema until today), `search_path` resolution is unambiguous —
there is no table a wrong schema could shadow. Hibernate `validate` is told to
read metadata `individually` so it resolves each mapped table through that path.
Flyway is pinned to `public` (`spring.flyway.schemas: public`) so its history
placement is independent of that `search_path`, and it runs V38's moves with
`public` in scope. No entity annotations change; no native query changes.

## Consequences

- **The schema boundary is real and tested.** Nine services can be lifted out
  without a database split under load; `SchemaIsolationTest` proves a role cannot
  cross the fence.
- **Still one process, still green, still reversible.** No FK dropped, no data
  transformed, no code path rerouted; the change is where tables live and who may
  read them. Rollback is `ALTER TABLE … SET SCHEMA public` for all 46 and dropping
  the roles — shipped behind a backup, verified live on a populated dev DB before
  merge (plan §3A PR 3 rollback).
- **Two plan items are consciously deferred** (per-service Flyway history, split
  platform tables) and one plan item consciously not done (dropping the merchant
  FK), each recorded above and marked in code. They land where they cost nothing:
  at each service's extraction, and at PR 4.
- **A new migration from V38 on must schema-qualify its tables.** The single
  linear V-sequence continues until a service is extracted, exactly as the plan
  says.
