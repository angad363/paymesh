# ADR-027 — Make platform roles grantable, by giving them no merchant at all

**Status:** Accepted
**Date:** 4 August 2026
**Supersedes nothing. Amends:** ADR-007 (tenant scoping), ADR-021 (roles carried, not discarded), ADR-024 (the two scopes)
**Migration:** V23

---

## Context

`Role` has had four constants since V2 and only two of them could ever be produced.
`user_roles.merchant_id` is `NOT NULL` and the primary key is `(user_id, merchant_id,
role)`, so a platform-wide grant had nowhere to live. V2's own comment says so, and says
what to do about it:

> a platform-wide grant has nowhere to live yet; when one is needed, make this column
> nullable and replace the PK with two partial unique indexes.

This was recorded as a one-line curiosity in open item 17 for several sessions. It is not
one. Follow the consequence:

1. `Merchant.register` lands on `PENDING_VERIFICATION`.
2. `MerchantStatusFilter` refuses every merchant-scoped write until a merchant is `ACTIVE`.
3. Activation is `PLATFORM_ADMIN`-only.
4. No `PLATFORM_ADMIN` can exist.

**A merchant registered through the public endpoint could never be activated, and an
unactivated merchant can do nothing at all.** The only reason the platform was usable is
that the Postman collection mints a token with `application-dev.yaml`'s published signing
key. That is honest on the `dev` profile and it is not a story that survives contact with
a real deployment — and Phase 2 asks for every PR to be verified end to end, which would
have meant inheriting the workaround eight more times.

There was a second problem hiding behind the first. `AuthenticatedCaller.requirePlatform
Admin()` read the claim as `PLATFORM_ADMIN:<any merchant>` — it scanned every merchant
scope for the role. That was safe **only because no endpoint could grant it**. The moment
one could, a `MERCHANT_ADMIN` granting `PLATFORM_ADMIN` at their own merchant would become
platform staff, with the power to lift their own suspension. Making the column nullable
without addressing this would have shipped a privilege escalation.

## Decision

**A platform role is held with no merchant at all, and the database refuses every other
shape.**

### 1. The schema (V23)

- Drop the primary key (a PostgreSQL PK implies `NOT NULL` on every column, so the column
  cannot become nullable while it stands).
- `merchant_id` becomes nullable. **`NULL` means platform-wide.** Not "unknown", not "any".
- Two partial unique indexes replace the PK:
  - `uq_user_roles_merchant_scoped` on `(user_id, merchant_id, role) WHERE merchant_id IS
    NOT NULL` — the old key, over the rows that still have a merchant.
  - `uq_user_roles_platform_scoped` on `(user_id, role) WHERE merchant_id IS NULL`.

  **Two, not one, and this is not stylistic.** In SQL `NULL <> NULL`, so a single unique
  index over `(user_id, merchant_id, role)` treats two identical platform grants as
  distinct rows and admits both. The platform half must not have the merchant in its key
  at all.

- `ck_user_roles_scope`, a biconditional: `PLATFORM_ADMIN` **if and only if** `merchant_id
  IS NULL`.

### 2. The claim: the separator is the scope

`MERCHANT_ADMIN:mrc_...` is held at that merchant. `PLATFORM_ADMIN`, with no colon, is
held across the platform. There is no third shape and no sentinel merchant id, so a token
**cannot express** "platform admin at one tenant".

A platform role arriving *with* a merchant is discarded rather than accepted at that
merchant — it is the pre-V23 shape and the escalation shape at once, and there is no
reading of it that grants less than the caller intended.

This is backwards-safe in the direction that matters: `AuthenticatedCallers` already
skipped colon-less entries, so no token issued before V23 gains authority.

### 3. `AuthenticatedCaller` gains a third component

`platformRoles`, separate from `rolesByMerchant`, because a platform role has no merchant
to be keyed by. A two-argument constructor keeps every existing call site compiling with
an empty set, which is the correct default for nearly every caller.

### 4. `POST` / `DELETE /api/v1/users/{userId}/platform-admin`

Its own route, `PLATFORM_ADMIN`-only, **deliberately not** a `role: "PLATFORM_ADMIN"` body
on the existing merchant-access route. Routing it there would mean any merchant admin
could promote themselves out of their tenant.

Demotion is refused when it would leave the platform with zero, checked as a **count**
rather than as "is the caller the target" — demoting the only *other* admin reaches the
same dead end from the other side.

### 5. The first one comes from a startup property

`paymesh.security.bootstrap-platform-admin-email`. An endpoint requiring a caller who
already holds the role cannot mint the first holder.

It **promotes an existing account and never creates one**. The human registers through the
ordinary endpoint; the operator names them and restarts. The rejected alternatives:

- **A seeded user in a migration** means a password hash committed to git, in a repository
  whose `DevelopmentSecretGuard` exists specifically to refuse committed secrets.
- **Inventing a password here** means a privileged account with a credential nobody chose.

It runs only while the platform has zero admins, so leaving the property set does not
re-promote somebody who was deliberately demoted.

## The escalation is closed in three independent layers

Because the failure mode is a tenant promoting itself out of its tenant, and the
application check is the one a future refactor can delete by accident:

| Layer | What refuses it |
|---|---|
| Database | `ck_user_roles_scope` will not store `PLATFORM_ADMIN` with a merchant |
| Domain | `User.grantRoleAt` and `RoleAssignment` will not build it |
| Claim | `AuthenticatedCallers` will not read the suffixed form as platform authority |

`PlatformAdminIntegrationTest` inserts straight through JDBC, past the upper two, because
the constraint is the layer that has to hold when the checks above it are gone.

## What this surfaced and did NOT close

**`MerchantStatusFilter` had its own copy of the `rolesByMerchant` scan.** Moving platform
roles out of that map fixed `requirePlatformAdmin()` and *silently broke the filter*: its
platform-staff bypass stopped firing, so a platform admin who also held a merchant role at
an inactive tenant could not activate anything — the very deadlock this ADR removes,
reached one layer up. **The first bootstrapped admin hits this every time**, because
registration assigns them `MERCHANT_ADMIN` at a merchant nothing has been able to activate.

Both readers now go through `AuthenticatedCaller.isPlatformAdmin()`. The lesson is the one
`AuthenticatedCallers` already records for the claim parser: two readers of one fact
eventually disagree, and the disagreement is silent.

**`JpaUserRepository.save` translated every `DataIntegrityViolationException` into
`UserEmailAlreadyExistsException`.** That was safe while `uq_users_email` was the only
constraint reachable from there. V23 added three more, and reporting one of those as
"email already registered" is a 409 naming the wrong field. It now matches on the
constraint name and lets anything else propagate.

## Consequences

- **`SERVICE_ACCOUNT` is still not grantable**, but no longer because the table cannot hold
  it. Machines authenticate with merchant API credentials (ADR-022), which belong to a
  merchant, so a platform-wide service account needs a different issuer. The constraint
  keeps it merchant-scoped rather than leaving the door ajar.
- **Promotion and demotion end every live session.** Promotion so the new claim is reissued
  at once rather than in up to fifteen minutes; demotion so the old one stops working. Both
  get their own `SecurityEventType`, because both would otherwise appear in the security log
  as somebody signing out.
- **The Postman collection no longer mints its own token.** It bootstraps a real
  `PLATFORM_ADMIN` and activates through it, which means the collection now walks the path a
  real integrator walks — the exact gap that let open item 16 hide for several sessions.
- **An access token still cannot be revoked before expiry** (open item 11). Ending the
  refresh-token family is what makes a demotion stick; the demoted admin's existing access
  token keeps working for at most fifteen minutes. Unchanged by this.
- **Granting still needs no consent from the target** (ADR-024's open note), and that now
  applies to platform promotion too. The exposure is smaller here — only platform staff can
  do it — but it is the same missing invitation flow.
