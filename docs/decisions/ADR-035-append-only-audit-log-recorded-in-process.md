# ADR-035: An append-only audit log, recorded in-process by the actions it describes

- Status: Accepted
- Date: 2026-08-22
- Scope: PayMesh Phase 2, PR 7. Migration V36. SDD §19.3.

## Context

Phase 2 built things worth being able to answer "who did this, when, and why"
about: merchant freezes, privileged role grants, webhook signing-secret
rotations, and system recovery actions. Nothing recorded them in one place.
`security_events` covers authentication only; the Ledger covers money; each
capability's own history table (`merchant_status_history`, …) covers its own
transitions in its own tenant scope. There was no cross-capability,
tamper-evident, compliance-facing record of privileged operational and security
actions.

This is the last PR of Phase 2 on purpose: its subjects are what the earlier
PRs created. Building it first would have been a log of things that had not
happened yet.

## Decision

### 1. Operational/security history here; financial journals stay in the Ledger

Two append-only logs, not merged. A ledger entry answers "where did the money
go" and is double-entry and balanced; an audit event answers "who touched the
machine" and carries an actor and a hashed IP. Merging them would put an actor
beside a debit and force a compliance reviewer's "list every privileged action"
through a double-entry query it does not want. The audit log deliberately
*overlaps* `merchant_status_history` and `security_events` for a few actions —
those tables are each one capability's record of its own transitions; the audit
log is the single place all privileged actions across all capabilities are read,
immutable by trigger. The overlap is the same shape as Reporting restating
Ledger figures.

### 2. Recorded in-process, inside the caller's transaction — NOT as an event consumer

Notification and Reporting are pure event consumers, because everything they
record already rides the outbox. **Audit's subjects do not.** Risk publishes no
event; webhook secret rotation publishes nothing; merchant and user freezes
publish nothing. Verified in code before choosing the shape.

So Audit exposes an in-process port, `com.paymesh.shared.audit.AuditRecorder`,
that a privileged service calls from **inside the same transaction** that commits
the action. The audit row and the action commit together: a committed suspension
always carries its audit event, and a rolled-back one leaves no false trail. This
is the "never unauditable" half of the governing invariant, the same reasoning
the transactional outbox uses.

A corollary, stated because it is a real consequence: **a failure to record is a
failure to act.** If the audit append throws, the privileged action rolls back
with it. For a security log that is correct — an action that cannot be audited
must not silently happen.

The port lives in `shared`, so Merchant, Identity and Webhook depend on it
exactly as they depend on `MerchantId` and `Clock`. The single implementation is
in `audit.infrastructure`. `ModuleBoundaryTest` keeps every arrow pointing at
Audit and none pointing out — the same shape `HoldingPeriodPolicy` (ADR-031) and
the event `EventHandler` have.

### 3. Append-only, enforced by a trigger, exactly as `ledger_entries` is

`audit_events` has a `BEFORE UPDATE OR DELETE` trigger that RAISEs (V36), copied
from the ledger's (ADR-018, V15). A trigger rather than a `REVOKE` for the same
reason the ledger gives: a revoke is granted per role, says nothing about the
migration owner, and is absent under Testcontainers where the test connects as
superuser. The integration test issues a raw `UPDATE`/`DELETE` and the database
refuses it with the application entirely out of the path. A correction to a
mistaken audit row is a *new* row that says so, never an edit.

### 4. Hashes, never plaintext, for before/after and IP

`before_hash`, `after_hash` and `ip_hash` are SHA-256, computed by the recorder
before a row exists. The log proves *that* state changed and *who* changed it,
never what a rotated signing secret was or which address a caller came from. The
recorder is the one place plaintext becomes a hash, so a caller cannot store a
secret by forgetting to.

**Ceiling, named:** the hashing is unsalted SHA-256 today. A low-entropy
before/after (`ACTIVE`→`SUSPENDED`) is guessable and an IPv4 digest is
brute-forceable. Acceptable now because those fields hold no secret a guess
reveals and **no wired caller passes an IP** — the recorders run below the HTTP
boundary. Upgrade path when a controller-layer caller passes a real IP:
HMAC-SHA256 under a configured pepper. Marked with a `ponytail:` comment in
`AuditHashing`.

### 5. Platform-staff read surface, and an async CSV export

`GET /internal/v1/audit-events` (list, filtered by merchant / action / actor /
window; newest first, capped) and `GET /internal/v1/audit-events/{id}` are
`PLATFORM_ADMIN`-only — this reads across tenants, which is not a tenant's power,
so it lives off `/api/` exactly like Notification's support endpoint. The list is
capped, no cursor; keyset pagination is the upgrade when a reviewer pages past
the cap (`ponytail:` marked).

`POST /internal/v1/audit-exports` records a `PENDING` row and returns `202`; a
scheduled generator (off under `dev`) renders the CSV, served from
`GET .../{id}` under `Accept: text/csv`. Copied verbatim from Reporting's
`report_exports` (ADR-034), including the TEXT-column ceiling (no object storage
exists in this project) and the over-cap `FAILED`. The differences: an audit
export has **no merchant owner** — it is a privileged read requested by platform
staff, carrying `requested_by` (the operator) and an optional `merchant_filter`.

## What is wired, and what is deliberately deferred

Wired now, because these are the reachable privileged actions today, all with the
actor already threaded through and running in a transaction:

- **Merchant freeze** — `ChangeMerchantStatusService` → `merchant.suspended` /
  `merchant.activated` / `merchant.closed`.
- **User privileged access** — `ManageUserAccessService` → `user.suspended`,
  `user.reactivated`, `user.closed`, `user.platform_admin_granted`,
  `user.platform_admin_revoked`, `user.access_granted`, `user.access_revoked`.
- **Secret rotation** — `RotateWebhookSecretService` → `webhook.secret_rotated`,
  recording the version bump as hashed before/after, never the secret.

Deferred, with the port ready and nothing else needed to add them:

- **Risk decisions.** `EvaluateRiskService` has no caller today — Payment's
  `confirm` (SDD §12.4) is not implemented, so risk evaluation does not run.
  Wiring it now would record something that never happens, the exact thing this
  PR's ordering exists to avoid. Add the call when `confirm` lands.
- **Payout retries / reconciliation recovery.** These are `SYSTEM`-actor actions
  (`ActorType.SYSTEM`, no operator, no IP). The schema and port already support
  them; add the `record(...)` call in `PaymentRepair`'s replay path when the
  operational need is real.
- **Startup platform-admin bootstrap.** Runs before an operator exists and already
  writes a `security_events` row; not audited as a `SYSTEM` event yet.

## Consequences

- The audit log is trustworthy because of the database, not the Java: the
  immutability trigger and the actor CHECK are proven by raw SQL with the app out
  of the path (`AuditPersistenceIntegrationTest`).
- A new privileged action is audited by one `auditRecorder.record(...)` call
  inside its transaction. Forgetting it is invisible to that capability's tests
  but caught by an end-to-end recording test where one is written.
- Because recording is synchronous and transactional, a broken recorder fails the
  privileged action. That is the intended trade for a security log; a capability
  that must never be blocked by audit would need the event-consumer shape, which
  its subjects cannot provide.

## Identifiers

`aud_` for an audit event, `aex_` for an audit export (ADR-003). Format enforced
by `is_prefixed_id` CHECKs in V36.
