# ADR-021: Make the lifecycle states reachable, and enforce them

- **Status:** Accepted
- **Date:** 2 August 2026
- **Related:** [ADR-007](ADR-007-enforce-authentication-and-tenant-scoping.md) (authn + tenancy),
  [ADR-008](ADR-008-cross-module-reads-through-a-consumer-owned-port.md) (consumer-owned ports),
  SDD §8, §9, §10

---

## 1. Context

An audit of Phase 1 after the Refund capability landed found the same defect in three places: a
lifecycle enum with exactly one reachable value.

| Enum | Declared | Ever produced |
|---|---|---|
| `MerchantStatus` | `PENDING_VERIFICATION, ACTIVE, SUSPENDED, CLOSED` | `PENDING_VERIFICATION` |
| `UserStatus` | `ACTIVE, SUSPENDED, CLOSED` | `ACTIVE` |
| `CustomerStatus` | `ACTIVE, BLOCKED` | `ACTIVE` |

`Merchant` had **no state-changing method at all** — `register`, `reconstitute`, and getters — while
`CLAUDE.md` cited `merchant.activate()` twice as *the* canonical example of the intent-method
convention. Nothing anywhere read `MerchantStatus`.

The consequences were not cosmetic:

- **No merchant could ever be suspended.** A compromised or fraudulent merchant could not be
  stopped — the most important operational control a payment platform has after authentication.
- **Every merchant transacted while unverified**, so verification was decorative.
- **`User.isActive()` was dead code**, checked at login and never false. A departed employee's
  account could not be disabled.

A fourth finding sat alongside them. `AuthenticatedCaller` held `(userId, Set<MerchantId>)`: the
resolver parsed `"<ROLE>:<merchantId>"`, kept the merchant and **discarded the role**. Authorization
was "are you scoped to this tenant" and nothing more, so `MERCHANT_USER` could refund exactly as
`PLATFORM_ADMIN` could, and three of the four declared roles were unreachable anyway because
registration always assigned `MERCHANT_ADMIN`.

## 2. Decision

Make every state reachable, enforce merchant status on every write, and read the role.

- Intent methods on all three aggregates, each with a real state machine.
- `merchant_status_history` and `customer_status_history`, in the shape the other four history
  tables already use.
- `MerchantStatusGate`, declared in `shared` and implemented by `merchant`, enforced by one filter.
- `CallerRole` carried through `AuthenticatedCaller`, with `requireSingleMerchantWith` and
  `requirePlatformAdmin`.
- **KYC submissions**, because without them the gate is a lock with no key (§4).

## 3. Why the gate is one filter and not a check per service

There are more than a dozen authenticated write paths across five capabilities. A rule enforced in
a dozen places is a rule that is missing from the thirteenth, and the thirteenth is written by
someone who does not know the rule exists. In a filter it is impossible to forget: a new endpoint
is covered the moment it is added.

**Writes only.** A suspended merchant reading their own orders is not a threat, and blocking it
makes the incident harder to resolve for everyone including the operator who suspended them.

**The exemptions are the interesting part**, and each one is a deadlock avoided:

| Exempt | Why |
|---|---|
| `POST /api/v1/merchants` | Unauthenticated; there is no merchant yet |
| `/kyc-submissions` | The one write an unverified merchant must make — refusing it makes ACTIVE unreachable |
| `/activate`, `/suspend`, `/close` | They necessarily act ON a non-ACTIVE merchant. Guarding them would make **suspension irreversible** |
| `/approve`, `/reject` | Approval is what activates; it cannot require the merchant to already be active |
| Provider and refund callbacks | HMAC-authenticated, no caller. **A payment already taken must settle even if the merchant was suspended meanwhile** — the customer's money moved, and refusing the callback strands it |

That last one is a deliberate asymmetry: **suspension stops new business, it does not abandon
business already in flight.**

## 4. Why KYC ships in the same change, and what happened when it did not

It was built without KYC first, on the reasoning that verification could follow later.

**84 tests failed with `403` where `201` was expected.** Registration produces
`PENDING_VERIFICATION`, the gate refuses every write from a non-ACTIVE merchant, and there was no
path out. Every newly registered merchant was frozen out of the platform permanently.

Every compile passed. Every unit test on the new aggregates passed. Only the full suite caught it.

A gate whose only entry state has no exit is not a partial feature, it is an outage. So approval —
which activates the merchant, in the same transaction, so a green submission can never sit beside a
frozen merchant — ships with the gate or the gate does not ship.

The alternative considered was **registering directly as ACTIVE** and deferring verification. It
was rejected because it would leave `PENDING_VERIFICATION` unreachable, which is precisely the
defect this ADR exists to fix — fixing two of three frozen enums while freshly freezing the third.

**No documents are stored and none are accepted.** PayMesh claims no compliance; a table holding
scans of passports would be there to make a checklist green rather than to verify anybody, and
would be the worst thing in the repository.

## 5. Why `CallerRole` is not `identity.domain.Role`

Same four constants, deliberately a separate type. `shared` may not import a capability —
`ModuleBoundaryTest` enforces it — and Identity is a capability. The token claim is a **published**
contract between the module that mints tokens and the platform that reads them, exactly as the
simulator publishes rather than shares the callback contract (ADR-017).

The cost of publishing is drift, so `AuthorizationBoundaryTest` asserts the two enums hold the same
constants. That is the notification a shared type would have suppressed.

`PLATFORM_ADMIN` is checked **platform-wide** rather than at a merchant, while every other role is
checked **at the resolved merchant**. Holding admin at merchant A must not authorize an admin action
at merchant B; but a caller who held `PLATFORM_ADMIN` "at" a merchant would be that merchant's own
staff able to lift their own suspension, which would make suspension advisory.

## 6. Consequences

**Good.**

- A merchant can be stopped. A user can be disabled. A customer can be blocked. All three are on
  the record with an actor and, where it matters, a required reason enforced by a CHECK.
- Every state in all three enums is reachable, and `MerchantGovernanceIntegrationTest` drives
  register → refused → submit → approve → transacting → suspend → refused → reinstate → transacting.
- `merchant.activate()` exists, so CLAUDE.md stops citing a method that was never written.

**Bad, and known.**

- **Registration now requires platform approval before a merchant can trade.** That is the correct
  model and it is a real behaviour change: self-serve signup is no longer self-serve. Every test
  fixture had to take the merchant through activation, which is itself the honest signal.
- **Suspension is not instant for reads-turned-writes already in flight**, and access tokens remain
  unrevocable for their 15-minute lifetime (open item 11, untouched). Suspending a merchant stops
  the next write, not one already inside a handler.
- **The V17 backfill activates every pre-existing merchant.** Without it, deploying this change
  would freeze every existing merchant — turning a security fix into an outage and punishing
  merchants for a control that did not exist when they signed up. Recorded as a SYSTEM transition
  with a stated reason so the audit trail explains the simultaneous activation.
- **The gate is one uncached primary-key read per authenticated write.** A cache would mean a
  suspended merchant kept trading for its lifetime, which is the window an incident is trying to
  close. If it ever measures slow the fix is explicit invalidation on suspend, not a TTL.
- **`SERVICE_ACCOUNT` is still unreachable.** Nothing mints one; it becomes reachable with API
  credentials, which are deliberately a separate change.

## 7. What is deliberately not here

API credentials (SDD §9.3) and the payment-method endpoints (SDD §10.3) were found by the same
audit and are **not** in this change. Neither is needed to make this one coherent, and machine
authentication is a security surface large enough to deserve its own review rather than a paragraph
inside somebody else's.
