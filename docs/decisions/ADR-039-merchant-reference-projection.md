# ADR-039: The merchant reference projection — consumers read an event-fed `merchant_ref`, and the `* → merchants` FK is dropped

- Status: Accepted
- Date: 2026-09-05
- Scope: PayMesh Phase 3A, PR 4. Migration V39. Follows ADR-038. Plan of record:
  `docs/phase-3-microservices-extraction-plan.md` §3A PR 4.

## Context

ADR-038 carved 46 tables into ten schemas but **kept** the one boundary-crossing
foreign key — `* → merchants(merchant_id)` on ~18 tables across seven schemas —
because dropping it there would have left a whole PR with a merchant reference no
longer guarded and nothing replacing it. This PR lands the replacement and drops
the FK in the same migration, so there is never an unguarded interval on the
money path.

The replacement is the pattern the plan names for all cross-service reference
data (plan §"Reference-data replication"): the owner emits lifecycle events; each
consumer keeps a narrow local projection fed by those events through its own
inbox; the projection is a cache of identity and status, never the authority.
After this PR no consumer reads the `merchants` table — the last thing that did,
the platform's merchant-status gate, reads the projection instead.

Nothing is extracted here. Still one process, one datasource, one Hibernate, one
Flyway history. The whole suite still passes with no broker (the relay runs
`in-process` under `dev`).

## Decision

### 1. A `merchant_ref` projection per consuming schema

Every schema that today carries a `* → merchants` FK gets a three-column read
model in that schema:

```
merchant_ref(merchant_id TEXT PK, status TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL)
```

with the same `is_prefixed_id(merchant_id, 'mrc_')` CHECK the FK columns already
carry. Six copies: `payment`, `ledger`, `settlement`, `risk`, `webhook`,
`engagement` — one per real service that carried the FK.

`platform` (outbox/idempotency) gets **no projection and keeps its FK**. Those
tables are not consumers reading merchant status, so the projection replaces
nothing for them — dropping their FK now would leave a merchant reference guarded
by nothing, the exact unguarded interval ADR-038 §3 refused. `platform` is also
the one schema that splits *per-service* at extraction (ADR-038 §5), so a single
`platform.merchant_ref` could never lift to any one service anyway. Its
`outbox_events → merchants` and `idempotency_records → merchants` FKs are dropped
then, with the tables. (`OutboxTransactionIntegrationTest` still proves the outbox
FK refuses an event naming a non-existent merchant.)

**Per-schema, not one shared copy** — the same call ADR-038 §5 made for the
outbox/inbox tables, for the same reason. A single `merchant_ref` read by every
service is "a shared database wearing a lanyard" (plan §"How services talk"): it
re-couples every consumer to one table and leaves the boundary the FK left open
still open. Per-schema costs more migration lines now and zero rework at
extraction — each service lifts its schema, projection already present and warm.
The one lever considered and rejected was a shared `platform.merchant_ref`; it
saves migration lines but defers the identical carve to extraction while
violating "a service owns its data."

`merchant`'s own `api_credentials → merchants` FK **stays**: it never crossed a
capability (both are `com.paymesh.merchant`, both in the `merchant` schema, ADR-038
§1), so it is free referential integrity, not a boundary to break. `merchant`
gets no projection — it owns the authority.

### 2. Merchant emits lifecycle events in the acting transaction

The merchant capability starts writing its outbox — it had none until now:

- `merchant.registered` from `RegisterMerchantService` (status `PENDING_VERIFICATION`),
- `merchant.activated` / `merchant.suspended` / `merchant.closed` from
  `ChangeMerchantStatusService`, alongside the audit event already emitted there.

Each carries `{ merchantId, status }`, aggregate type `MERCHANT`, aggregate id the
merchant id, version 1. `RegisterMerchantService` is wrapped in a
`TransactionTemplate` so the insert and the outbox row commit together — the
ADR-010 rule, previously unneeded there because it wrote no event.

**Divergence from the plan's prose.** The plan says events "sourced from
`merchant_status_history`." We emit them in the acting transaction via
`OutboxWriter.append`, the codebase's universal pattern (ADR-010/016), rather than
scanning the history table. In-transaction emission gives the same atomicity the
outbox was built for; a separate history-scanner would be a second, weaker
mechanism for a guarantee we already have. `merchant_status_history` stays the
merchant's own record of its transitions, unchanged.

### 3. One projector writes every copy; the gate reads via `search_path`

`merchant_ref` **cannot be a bare-named JPA entity.** ADR-038 §6 makes unqualified
entity names resolve across schemas *because all 46 table names are globally
unique*. Seven tables all named `merchant_ref` break that: a bare
`@Table(name="merchant_ref")` would resolve to whichever copy `search_path` hits
first, and `ddl-auto=validate` would check only that one. So the projection is
reached by **schema-qualified SQL**, not by an entity:

- **Writes:** one `MerchantRefProjector` (an `EventHandler`, four beans one class —
  the `NotificationEventHandler` shape) upserts into *every* copy on each
  `merchant.*` event, looping a fixed schema list with schema-qualified
  `INSERT … ON CONFLICT`. It runs inside the dispatcher's transaction and throws to
  retry, like every handler. All copies are written in one transaction, so they
  are identical by construction. `ponytail:` fan-out write to N schema copies; at
  extraction each service keeps only its own and this loop collapses to one.
- **Reads:** the gate issues an unqualified `SELECT status FROM merchant_ref WHERE
  merchant_id = ?`. Because every copy is identical, the copy `search_path`
  resolves is authoritative-equivalent — the ambiguity ADR-038 §6 warns about is
  harmless here, deliberately, and only because the projector keeps the copies in
  lock-step.

This is the one place a monolith-era convenience (one projector, one process)
stands in for what becomes per-service at extraction. It is `JdbcTemplate` — the
first use in the codebase, but Spring-core and already on the classpath (via
`spring-data-jpa`); seven near-identical JPA entities to avoid it would be exactly
the boilerplate the projection is small enough to make absurd.

### 4. The gate gains a third outcome: `UNKNOWN` is retryable, `DENIED` is 403

Reading a *projection* instead of the authoritative table introduces lag the
direct read never had — and in the monolith the relay is asynchronous and **off
under `dev`**, so a just-registered or just-activated merchant is routinely absent
from or stale in the projection for a relay cycle. The direct read was always
current; the projection is eventually consistent even in one process.

`MerchantStatusGate` therefore stops returning `boolean` and returns
`MerchantTransactability`:

| Verdict | Projection state | Filter response |
|---|---|---|
| `ALLOWED` | present, status `ACTIVE` | request proceeds |
| `DENIED` | present, status not `ACTIVE` | `403 MERCHANT_NOT_ACTIVE` (unchanged) |
| `UNKNOWN` | **absent** | `503 MERCHANT_NOT_YET_AVAILABLE`, retryable |

`UNKNOWN → 503` is the "retryable, not a 500" the plan's verification requires: a
client that registered and immediately transacts retries until the projection
catches up, the same shape as any at-least-once path.

**Accepted ceiling.** A merchant *present but with a stale status* — activated but
the `merchant.activated` event not yet projected — reads as `DENIED` and gets a
brief `403`, not a `503`, because status alone cannot distinguish "genuinely
pending" from "activated-but-lagging." The window is one relay cycle and
self-heals. A heuristic to treat recent transitions as retryable would be
speculative complexity for a sub-second window (plan §"eventual consistency
surfacing as bugs" accepts exactly this). `ponytail:` stale-status → 403 for one
relay cycle; revisit only if the window is ever user-visible.

This leaks nothing new: only authenticated callers reach the gate, and a valid
token always names a real merchant, so `UNKNOWN` for a legitimate caller is always
propagation lag, never a probe.

### 5. Drop each `* → merchants` FK; keep the format CHECK

V39 drops the 17 `fk_*_merchant` constraints across the six consuming service
schemas (payment 5, ledger 2, settlement 2, risk 2, webhook 3, engagement 3). The
two `platform` FKs are kept (see §1). The per-row `is_prefixed_id(merchant_id,
'mrc_')` CHECK stays on every column — it never needed the foreign table, only the
shape (ADR-029). Composite tenant FKs that point *within* a service (`orders →
customers` on `(merchant_id, customer_id)`) stay; only the arrow into `merchants`
is cut.

For the event-fed rows (ledger, engagement, webhook deliveries — inserted by
handlers, not authenticated writes), the dropped FK's guarantee is replaced by
*provenance*: the `merchant_id` came from a domain event the producer emitted for
an already-validated merchant, so it names a real one without a local foreign key.
For the authenticated-write rows (payment, settlement config), the gate on the
write path is the replacement.

What the FK guaranteed — no row for a non-existent merchant — is now guarded by
the gate on the authenticated write path (a bogus merchant id cannot be in a valid
token) plus the format CHECK on shape. The projection replaces the *read* the gate
used to do against `merchants`, not the FK's insert-time enforcement, which the
authenticated path already made redundant.

## Consequences

- No consumer reads the `merchants` table. The merchant capability is now a pure
  emitter of its lifecycle; every other schema reads its own projection. The last
  boundary-crossing FK ADR-038 left standing is gone.
- Read-your-write across the merchant boundary is no longer free, and is now a
  client-facing fact surfaced as `503 MERCHANT_NOT_YET_AVAILABLE`. This is the
  first place the platform returns a retryable "not yet consistent" error; the
  Ledger and payment paths will lean on the same shape.
- **Suspension is now eventually consistent, which partially walks back ADR-021.**
  The old `MerchantStatusGateAdapter` read the `merchants` table directly and was
  emphatically *not* cached, precisely so a suspended merchant could not keep
  trading. The projection *is* a cache: a suspended (or closed) merchant keeps
  transacting until `merchant.suspended` propagates — one relay cycle. This is the
  unavoidable price of the merchant being a separate service later (its status
  cannot be read synchronously across a process without a network hop the money
  path refuses to add per write). The mitigation is that the relay runs
  continuously in production, so the window is seconds, and suspension is a policy
  action, not a money-integrity one — a few extra seconds of trading does not lose
  or duplicate money, which is the governing invariant. If that window ever needs
  to be zero for a class of action, the answer is a synchronous gate call to the
  merchant service for that action, not un-caching the projection. Flagged here
  because it is a real behavior change reviewers must weigh.
- Every test that changes merchant status and then asserts the gate must now drive
  the relay (`PublishOutboxEventsService.publish()`) between the change and the
  assertion — the projection does not update until it runs (the relay is off under
  `dev`). This is not test scaffolding for its own sake; it is the eventual
  consistency being exercised honestly.
- At extraction (3B+), each service keeps only its own `merchant_ref` and its own
  projector fed from Kafka; the monolith's fan-out projector and the shared gate
  collapse to per-service copies with no contract change.
- The merchant FK is re-attachable for rollback: it is `ALTER TABLE … ADD
  CONSTRAINT` against the still-present `merchants` table, and the gate can re-point
  at the merchant repository, until the merchant service is extracted and the table
  leaves the process for good.
