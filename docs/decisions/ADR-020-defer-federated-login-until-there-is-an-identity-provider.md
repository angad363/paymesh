# ADR-020: Defer federated login until there is an identity provider

- **Status:** Accepted
- **Date:** 2 August 2026
- **Related:** SDD §8.3, §8.4

---

## 1. Context

SDD §8.3 specifies `GET /v1/auth/oauth2/callback/{provider}` and §8.4 an `oauth_accounts` table.
Neither is built, and the Phase 1 audit listed both as gaps.

There is no identity provider to integrate with. PayMesh is not registered as an OAuth client
anywhere, and it processes no real money that would justify becoming one.

## 2. Decision

Do not build it. Password login stays the only authentication for humans.

## 3. Why

Three options, and two of them are worse than absence.

**A stub endpoint** — the route and table exist, returning `501` or accepting a caller-supplied
subject. This is the option to argue against hardest: *an endpoint that authenticates nobody is
worse than an absent one*, because it appears in a security review as a login path and has to be
disproved rather than simply not being there. A caller-supplied subject would be an authentication
bypass with a checklist tick on it.

**A simulated identity provider**, alongside the payment simulator — `authorize`, `token`, state
tokens, PKCE, account linking, `oauth_accounts`. This is honest and it is a whole capability,
comparable in size to the Provider Simulator. The difference from that simulator is decisive: the
payment simulator unblocked five payment states that were otherwise unreachable and a whole
capability that could not be tested. A fake IdP unblocks nothing — password login already works and
nothing downstream depends on federated identity.

**Defer it**, which is this decision.

## 4. Consequences

- `oauth_accounts` and the callback route remain unbuilt. `docs/project-status.md` records them as
  a known gap rather than as an oversight.
- Adding them later is purely additive: no table shipped in this change would need altering, and
  `users` already has the shape an OAuth account would link to.
- **Password login is therefore the only human authentication**, which makes the refresh-token
  rotation and reuse-detection in V2 the whole of session security. That was already true; this
  records that it stays true deliberately.
