# ADR-024: Disabling people, at the two scopes that mean different things

- **Status:** Accepted
- **Date:** 3 August 2026
- **Closes:** [ADR-023](ADR-023-finish-the-lifecycle-claims-and-give-the-token-table-a-writer.md) §5's
  first outstanding item
- **Related:** [ADR-021](ADR-021-make-the-lifecycle-states-reachable-and-enforce-them.md) (roles +
  status gate), [ADR-007](ADR-007-enforce-authentication-and-tenant-scoping.md) (tenancy), SDD §8

---

## 1. Context

`UserStatus` has had three values since V2 and only `ACTIVE` was ever produced. ADR-021 added
`User.suspend`, `reactivate` and `close` and claimed the states were reachable; **nothing called
them**. ADR-023 corrected the record and left the gap open rather than guessing, with one question
outstanding:

> *Who may disable a user — platform staff, or a merchant admin over their own staff?*

**A departed employee's account could not be disabled.** That was the last frozen lifecycle enum in
the platform.

## 2. The question was wrong, and that is the decision

It assumed one operation. There are two, and conflating them is a real security defect rather than
a modelling preference:

| | Scope | Who | What survives |
|---|---|---|---|
| `DELETE /api/v1/users/{id}/merchant-access` | One merchant | `MERCHANT_ADMIN` | The **account**. Their roles elsewhere are untouched. |
| `POST /api/v1/users/{id}/suspend\|reactivate\|close` | The platform | `PLATFORM_ADMIN` | Nothing — the human is barred everywhere. |

**Why conflating them is a defect.** `AuthenticatedCaller` has always supported a user holding
roles at several merchants — the accountant serving two businesses is named in its javadoc. If
"remove this employee" disabled the account, **merchant A could lock somebody out of merchant B**.
That is a cross-tenant action performed by a tenant, which is precisely what ADR-007 exists to
prevent, and it would have arrived disguised as an obvious feature.

So the merchant-scoped operation removes `user_roles` rows and leaves `UserStatus` alone. The
platform-scoped one moves `UserStatus` and is not a tenant's to call.

## 3. Grant ships with revoke

Revocation without a grant is one-way: a merchant that removed somebody by mistake, or re-hired
them, would need PayMesh to intervene. **An operation whose only undo is a support ticket is one an
admin will not use confidently**, and an admin who will not revoke access is the problem this ADR
is trying to solve.

`POST /api/v1/users/{id}/merchant-access` grants a role at the caller's merchant. It cannot grant
`PLATFORM_ADMIN` — a tenant promoting somebody to platform staff is the one escalation the whole
role model exists to prevent — and it refuses a role the user already holds there, because that
almost always means the caller believed something false about the current state.

## 4. Suspension ends every live session, and that needed isolating to prove

The refresh path already re-reads the user and revokes the family when it cannot authenticate. So
suspension would bite at the next refresh anyway, and `revokeAllForUser` on top of it is defence in
depth: **the bar should not depend on that one check still being there.** If it were ever removed or
bypassed, a live refresh token would keep minting access tokens for up to thirty days after the
human was barred.

That redundancy made the first test worthless. Asserting "a suspended user cannot refresh" passes
whether or not the tokens are revoked, because the status re-check refuses it either way —
**measured, not assumed**: deleting the revocation left the whole suite green. The test now asserts
on the stored token rows before any refresh is attempted, and deleting the revocation turns exactly
that one test red.

**An access token already issued still works for its remaining lifetime** — at most fifteen minutes,
because nothing checks a denylist (open item 11, unchanged). Suspension is therefore "within a
quarter hour", not instant.

## 5. Platform staff are not a merchant transacting

`MerchantStatusFilter` refused every one of these routes with 403.

A platform admin's token carries a merchant scope because the claim format requires one, but their
authority is platform-wide and the merchant they nominally sit at may not exist — or may be the very
one they are about to suspend. Gating them on merchant status means **every platform route anywhere
in the API is 403**, which is what happened here and what the merchant lifecycle routes needed a
path exemption for in ADR-021.

The filter now passes any caller holding `PLATFORM_ADMIN` and the path-suffix exemptions shrink to
`/kyc-submissions`, the one genuinely merchant-side case. That also retires a list of generic verbs
— `/activate`, `/close` — which was itself a review finding in ADR-021's PR: a bare `endsWith` on
those words would exempt any future endpoint that happened to end in one.

**This widens nothing a merchant can reach.** Registration only ever assigns `MERCHANT_ADMIN`, and
`ck_api_credentials_role` refuses a `PLATFORM_ADMIN` key outright. The only way to hold the role is
for platform staff to have issued it.

## 6. Smaller decisions

- **A merchant admin may not revoke their own access.** They are the only role that can grant it
  back, so a merchant with one admin who revoked themselves would need PayMesh to intervene.
- **"No such user" and "holds no role at your merchant" are one 404.** Telling them apart would let
  any merchant admin enumerate every user id on the platform.
- **Losing the last role is allowed.** The account survives with no tenant to act for, which is a
  real state — somebody between assignments — and `requireSingleMerchant` already refuses them
  cleanly.
- **The four events get their own `security_events` types** (V20). Suspension revokes sessions, so
  logging it as `LOGGED_OUT` would be true and useless: the security log would show a bar as a
  sign-out, and "who was barred and when" would be unanswerable from the one table that exists to
  answer it.
- **No password hash reaches an administrator.** It is what an offline attack runs against, and an
  admin listing their staff has no use for it.

## 7. Consequences

**Good.**

- Every lifecycle enum in the platform is now reachable. That was three when the audit ran.
- A merchant can remove a departed employee themselves, without a support ticket and without
  reaching into another tenant.
- The platform-admin rule is general, so the next platform route does not need a path exemption.

**Bad, and known.**

- **Suspension is not instant.** Up to fifteen minutes of access-token lifetime remains, and closing
  that is open item 11 — a denylist or much shorter tokens, neither of which is free.
- **There is no "list every user" for platform staff.** They can act on a user id they already have;
  finding one is not a route. Deliberate: a platform-wide user search is a reporting concern and
  a tempting thing to leave unaudited.
- **`SERVICE_ACCOUNT` is still unreachable.** Nothing mints one, as recorded in ADR-022.
