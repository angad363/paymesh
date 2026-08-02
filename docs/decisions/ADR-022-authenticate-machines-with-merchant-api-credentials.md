# ADR-022: Authenticate machines with merchant API credentials

- **Status:** Accepted
- **Date:** 2 August 2026
- **Related:** [ADR-007](ADR-007-enforce-authentication-and-tenant-scoping.md) (authn + tenancy),
  [ADR-021](ADR-021-make-the-lifecycle-states-reachable-and-enforce-them.md) (roles + status gate),
  SDD §9.3, §9.4, §10.3, §11.3

---

## 1. Context

SDD §10.3 and §11.3 both say customers and orders are created with a **Merchant API key**, and
§9.3 specifies endpoints to create and revoke one. None of it existed.

So a merchant's backend integrating with PayMesh had to authenticate **as a human with a
password** — the one credential a server must never hold, and one that carries far more than the
integration needs: the ability to log in interactively, rotate its own session, and act anywhere
else that person holds a role. Server-to-server integration was impossible as specified, and the
available workaround was worse than the gap.

## 2. Decision

`Authorization: ApiKey ak_<prefix>.<secret>`, verified inside the Spring Security chain, producing
a caller indistinguishable from a human one.

## 3. The filter runs inside the security chain, and it has to

This is the detail that decides whether the feature works at all.

The chain ends with `.anyRequest().authenticated()`. A filter registered as an ordinary
`FilterRegistrationBean` — which is how every other filter in this codebase is registered — runs
**after** the security chain. An ApiKey request would therefore be refused `401` before the filter
ever saw the header.

So it is inserted with `addFilterBefore(..., BearerTokenAuthenticationFilter.class)`. Verified by
removing that line: **7 of 11 integration tests go red**, all with 401.

That placement has a consequence for module boundaries. `SecurityConfiguration` lives in `shared`,
and `shared` may not import a capability — so the filter lives in `shared` too, and the credential
store answers through `ApiKeyAuthenticator`, a port implemented by `merchant`. Exactly the shape
`MerchantStatusGate` uses (ADR-021).

A second `FilterRegistrationBean` with `setEnabled(false)` stops Boot auto-registering the same
filter in the servlet chain, where it would run a second time in the position that does not work.

## 4. A key is indistinguishable from a human downstream

The filter mints an **in-memory, unsigned `Jwt`** carrying the same `roles` claim a login produces,
rather than teaching `AuthenticatedCallers` a second principal type.

That is not a shortcut, it is the point: two principal types would be two authorization paths, and
they would eventually differ — a key granted something a token did not, or the merchant status gate
applied to one and not the other. Because the shapes are identical, **every rule written for humans
automatically holds for machines**, and the tests prove it: a key belonging to a suspended merchant
is refused by ADR-021's gate, and a `MERCHANT_USER` key cannot mint a credential.

The token is never signed and never leaves the process. It is a claims carrier, not a credential.

`sub` is the **credential's** id, not a user's. An API key is not a person, and attributing a
machine's writes to whoever created the key puts the wrong name in every audit row it produces.

## 5. SHA-256, not bcrypt

The secret hash is verified on **every API request**, where a deliberately slow KDF would be a
self-inflicted denial of service.

bcrypt's cost exists to make *guessing a low-entropy human password* expensive. This secret is 32
bytes from `SecureRandom`; it is not guessable at any hash speed, so the cost would buy nothing and
be paid on every call. Identity's password hashing is bcrypt and must stay bcrypt, because a human
chose that input. **The difference is the entropy of what is being hashed, not carelessness in one
of them.**

Both sides are compared with `MessageDigest.isEqual`, which does not short-circuit on the first
differing byte — a plain `equals` leaks the secret to anyone who can measure response time.

## 6. Smaller decisions worth recording

- **The public prefix is unique platform-wide, not per merchant.** Authentication is what
  *establishes* the merchant, so there is no tenant to scope the lookup by yet. A per-merchant key
  space would force the lookup to guess a tenant first, and the guess would itself be a
  cross-tenant oracle.
- **The secret is returned exactly once**, in a response type (`CreatedApiCredentialResponse`) that
  is *different from the one every read uses*. A nullable `secret` field on the ordinary response
  would be one forgotten branch away from leaking on every list.
- **Revocation is a timestamp, not a delete.** A deleted credential cannot answer "was this key
  live when that payment was taken", which is the question an incident asks.
- **No key may be `PLATFORM_ADMIN`.** A string in a config file should not be able to suspend
  merchants or approve KYC. Refused by the aggregate and by `ck_api_credentials_role`.
- **Unknown, malformed, revoked and wrong-secret are one answer.** Distinguishing them confirms
  which prefixes exist, and a revoked key answering differently tells an attacker they once had
  something real.

## 7. Consequences

**Good.**

- A merchant backend can integrate without holding a person's password, which is what SDD §10.3 and
  §11.3 assumed all along.
- Machines inherit the entire authorization model for free, including controls added after this.

**Bad, and known.**

- **`last_used_at` is written on every authenticated request.** It is best-effort in its own
  transaction with failure swallowed, so it cannot fail a payment — but it is still a write per
  call on one row. If it measures hot, the fix is to sample it or move it out of band, not to make
  it strict.
- **There is no key expiry.** A credential is live until revoked. Rotation is therefore a manual
  discipline rather than something the platform enforces, and nothing surfaces "this key is two
  years old" beyond `last_used_at` and `created_at` being visible.
- **No scopes beyond the role.** SDD §9.4 lists `scopes` on the table; this uses the role instead,
  so a key is as broad as the role it holds. Per-endpoint scopes would be finer, and would be a
  second authorization model to keep in step with the first — which §4 argues against.
- **`SERVICE_ACCOUNT` remains unreachable.** It would be the natural role for a key, and using it
  would have meant a role no human can hold and therefore a second path through every check. The
  key holds a merchant role instead.
