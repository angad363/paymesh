# ADR-007: Enforce authentication at the filter chain and tenancy at the data

## Status

Accepted

## Context

The identity capability issued access tokens that nothing checked. Every endpoint
was open, and the merchant read endpoint would return any merchant to anyone who
knew its id — which ADR-003 makes hard to guess, but guessing was never the
threat model.

Two different questions needed answering, and conflating them is the usual way
tenant isolation fails:

1. **Who is calling?** A property of the request, answerable before any handler
   runs.
2. **Which merchant's data may they touch?** A property of the *row* being
   accessed, unanswerable at the edge because the edge cannot see which row that
   is.

## Decision

**Authentication is enforced by the filter chain.** Spring Security's
`BearerTokenAuthenticationFilter` reads the `Authorization` header and verifies
the JWT. The rule is default-deny: `anyRequest().authenticated()`, with public
routes listed explicitly. A new endpoint is protected by virtue of existing;
opening one is a deliberate line, never an omission.

Public routes are the auth endpoints (getting a token cannot require a token),
`POST /api/v1/merchants` (self-service onboarding precedes having an account),
and the health/info actuators.

The filter chain verifies with **the same decoder that mints tokens**, not a
second one built from the same property. Two decoders are two places for the key
length check, accepted algorithm and clock skew to drift apart, and a verifier
that disagrees with the issuer fails at the worst possible moment.

**Tenancy is enforced next to the data.** The verified token is turned into an
`AuthenticatedCaller` — user id plus the merchants they hold roles at — which
controllers declare as a parameter. Controllers never read claims, and no request
body, path or query parameter carries a merchant id. Services then take the
merchant as an argument that acts as authorization rather than as a filter.

**Cross-tenant access returns 404, never 403.** A 403 confirms the id exists,
which turns any scoped endpoint into an oracle for enumerating tenants. "Not
yours" and "not there" are made indistinguishable.

**Ambiguous scope fails loudly.** A caller holding roles at several merchants is
legitimate, but which tenant a write belongs to cannot be inferred. Merchant-scoped
endpoints reject that caller (403 `NO_MERCHANT_SCOPE`) until an explicit selector
exists, because guessing eventually writes a row under the wrong merchant.

## Consequences

Tenant isolation no longer depends on every future controller remembering to
filter. The type signatures carry it: a controller that wants a tenant must ask
for an `AuthenticatedCaller`, and the repositories have no single-argument lookup
to misuse.

Costs and open edges:

- Roles are baked into the access token at login, so a role granted afterwards is
  not visible until the token is refreshed. Standard for stateless JWTs, and the
  15-minute lifetime bounds it.
- An access token cannot be revoked before it expires; nothing checks a denylist.
  Its lifetime *is* the revocation window.
- `POST /api/v1/merchants` is unauthenticated by design and is therefore the first
  endpoint that needs rate limiting.
- Authorization is currently binary per tenant: holding any role at a merchant
  grants everything at that merchant. Distinguishing `MERCHANT_ADMIN` from
  `MERCHANT_USER` waits until two endpoints actually differ by permission.
