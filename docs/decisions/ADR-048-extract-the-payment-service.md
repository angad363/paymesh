# ADR-048: Extract the payment service — the synchronous money path leaves as one deployable

- Status: Accepted (ApiKey decided as option 3, §4)
- Date: 2026-10-20
- Scope: PayMesh Phase 3D, PR 13. New `payment/` module (port 8089), the tenth deployable, carrying four
  capabilities that share one transactional boundary: customer, order, payment and refund. Own Flyway
  history, own `payment` schema reached as the fenced `payment_svc` role. Follows ADR-018 (the ledger
  posts from events), ADR-021 (the merchant status gate), ADR-039 (the `merchant_ref` projection),
  ADR-044 (the synchronous risk hop and its by-amount fallback), ADR-046 §4 (ApiKey deferred to this PR),
  and ADR-047 (the most recent extraction, with its exclusive-ownership pattern). Plan of record:
  `docs/phase-3-microservices-extraction-plan.md` §3D PR 13.

## Context

The plan calls PR 13 the point of no easy return: order, payment and refund share the tightest
transactional coupling in the system, so they leave together, and the one network hop that would hurt —
order to payment — is removed by keeping them in one service. Four facts from the code shape the
decisions below.

1. **The ledger already posts money from events.** Its only consumers are `payment.succeeded` and
   `refund.succeeded` (ADR-018, ADR-031). The plan's "confirm calls the Ledger API to post money" does not
   match what the code does, and this ADR records that rather than building the API the code does not need.
2. **Payment already reads merchants locally.** Order, customer and refund read the `merchant_ref`
   projection (ADR-039). There is no cross-service table read to remove.
3. **Risk is already a seam.** Payment's confirm calls risk through `RiskCheck`; `paymesh.risk.mode=http`
   selects the HTTP implementation (ADR-044).
4. **The provider speaks to a hard-coded monolith address.** `provider-sim` posts callbacks and payout
   answers to `localhost:8080`, not through the gateway.

## Decision

### 1. Divergence from the plan, recorded

**The plan's "confirm calls the Ledger API to post money" is not adopted.** Posting stays event-driven:
payment's outbox emits `payment.succeeded` and `refund.succeeded`, and the ledger posts from them, as it
does today. A synchronous ledger call would add a network hop to the money path and a second writer of the
ledger's state, against ADR-018's rule that every journal traces to a committed state change. The ledger
needs no API for PR 13. PR 14 (the ledger's own extraction) gives it an idempotent posting API for other
callers, and payment does not become one of them.

The plan's "consumes `ledger.transaction.posted`" is likewise unnecessary: nothing in payment waits on the
ledger's posting.

### 2. Schema, fenced role, and the platform tables

The `payment` schema (ADR-038) holds `customers`, `customer_status_history`, `payment_method_tokens`,
`orders`, `order_state_history`, `payment_intents`, `payment_attempts`, `payment_state_history`,
`provider_callbacks`, `refunds`, `refund_state_history`, and `refund_callbacks`. `V1` adopts them
(`baseline-on-migrate`), and `V2` creates the platform tables with a nullable `outbox_events.merchant_id`.
Connects as `payment_svc`, which has no grant on `ledger`, `merchant` or `risk`.

### 3. Risk: the existing seam, unchanged

Payment calls risk over HTTP through `RiskServiceHttpCheck`. In the service, `paymesh.risk.mode=http` is the
only value that matters. The fail-closed-by-amount policy from ADR-044 (below the threshold fail open,
at or above it fail closed with 503) is not changed. The risk service's own circuit breaker and timeouts
are unchanged.

### 4. ApiKey: a local credential projection (decided)

ADR-046 §4 deferred ApiKey authentication on capabilities other than merchant. Payment is the first where
machine callers are the common case. Three options were weighed; **option 3 is chosen**.

- **Option 1 — JWT-only on payment.** ApiKey callers refused; integrations mint a JWT through identity first.
  *Rejected:* breaks every existing ApiKey integration at cutover and adds a token-mint round-trip per token.
- **Option 2 — synchronous verify against merchant.** *Rejected:* every machine request on the money path
  would depend on the merchant service, so a merchant outage stops payments at the door.
- **Option 3 — local credential projection (chosen).** The merchant service publishes
  `merchant.api_credential.issued` and `merchant.api_credential.revoked` from its outbox (same transaction as
  the change). Payment's `api_credential_ref` read model (`api_credential_id, merchant_id, public_prefix,
  secret_hash, role, revoked_at`) is fed by a projector on those events, the ADR-039 pattern. Verification
  is local: no synchronous call, no change for integrations. *Tradeoff, stated:* the secret hashes are a
  second copy in payment's schema (SHA-256 of 32-byte random secrets, not guessable, but still sensitive),
  and revocation is eventually consistent, so a revoked key can work for the projection lag. Both are
  accepted the way ADR-039 accepted suspension lag. The monolith keeps emitting these events too, so a
  rolled-back deployment still feeds the projection.

### 5. Provider callbacks and the simulator's address

Provider callbacks (`/internal/v1/provider-callbacks/**`) and refund callbacks
(`/internal/v1/refund-callbacks/**`) move to the payment service. The gateway gains `paymentInternalRoutes`
(`@Order(0)`, subset of `/internal/**`) forwarding both prefixes to `paymesh.gateway.payment-uri`
(`:8089`), unrate-limited and unauthenticated at the edge, HMAC-checked at the deployable, the posture
these callbacks already have.

`provider-sim`'s `callback-url` and `payout-callback-url` are re-pointed from `localhost:8080` to the
gateway (`:8081`), so the simulator's callbacks reach whichever service owns the prefix. Re-pointing through
the gateway, not directly at `:8089`, is the same rule ADR-041 used: a single place decides which process
answers.

### 6. Gateway prefixes

Client routes `/api/v1/customers/**`, `/api/v1/orders/**`, `/api/v1/payment-intents/**` and
`/api/v1/refunds/**` forward to `payment-uri`, rate-limited as client traffic with `@Order(0)` (each is a
subset of `/api/**`). Payment's own edge rules mirror the monolith's: public paths stay as they are today,
and everything else under these prefixes requires a token. The ApiKey option in §4 changes only whether
an `ApiKey` header is accepted at the edge, not the prefixes.

### 7. Dual path and exclusive ownership of in-flight intents

The monolith keeps every payment class, its `payment` schema access and its outbox producer, so rollback
is re-pointing the gateway's `payment-uri` (and `provider-sim`'s callbacks, already routed through the
gateway) back to the monolith. There is no monolith migration and no mode flag, the ADR-045/046 shape.

The shared `payment` schema makes state safe across the switch in one direction and not the other:

- **Callbacks are idempotent across processes.** `provider_callbacks` is keyed on
  `(provider, external_event_id)` (ADR-012), so a callback applied by one process and redelivered to the
  other is refused, not double-applied, whichever process receives it.
- **Sweepers are not.** The PROCESSING-timeout (ADR-015), order-expiry (ADR-014) and abandoned-intent
  sweepers each run on a timer in both processes. The same exclusive-ownership rule as ADR-047 §2 applies:
  one flag per sweeper, true in exactly one process, flipped in one runbook step. Two processes running the
  PROCESSING timeout can each fail a live intent, so this is not optional.

**Cutover runbook (proposed):** (1) turn off the monolith's payment sweepers; (2) drain the monolith's
outbox so `payment.*` and `refund.*` reach the ledger; (3) re-point the gateway's payment routes and
provider-sim's callback URL; (4) turn on the payment service's sweepers. In-flight PROCESSING intents keep
their state in the shared schema, and their callbacks land on whichever process the gateway now routes to,
which is safe by the callback idempotency above.

## Consequences

- The money path becomes one deployable with no new synchronous dependency beyond risk (already present).
  The credential projection (§4) is the one new event-fed copy of sensitive data.
- The plan's ledger-API step is dropped for PR 13, and PR 14 must not assume one exists.
- Sweeper exclusivity is the step most likely to be done wrong at deploy time, so it is part of the
  runbook, not a footnote.
- provider-sim's callbacks now depend on the gateway being up, a new availability edge for the simulator
  that the gateway's own rate-limit bypass for callbacks already covers.
- The `shared` subtree is duplicated into a tenth module until PR 16, the stated recipe cost.

## Review amendments (PR 13)

The review of this PR corrected four claims in the sections above and added one fix.

- **ApiKey is still refused at the gateway edge (corrects §4).** The gateway's edge has always been
  JWT-only (ADR-040), so ApiKey traffic through `:8081` was already refused before this PR. The local
  projection makes ApiKey work for callers that reach payment directly; it does not reopen the edge.
  Letting `Authorization: ApiKey` through the edge is an edge-auth change and is a follow-up, not part of
  this PR. "No change for integrations" holds only for direct callers until that follow-up lands.
- **Idempotency does not cross the cutover (addendum to §7).** `idempotency_records` now exists in both the
  monolith's `platform` schema and payment's own schema. A retry that crosses the cutover misses the first
  attempt's record and runs the write again. Mitigation in the runbook: drain in-flight write requests before
  re-pointing the payment routes, and treat a cutover-straddling retry as a duplicate to check by hand.
- **Sweepers default off in payment (fix).** Payment's four sweepers default to `enabled: false`, so a stock
  start cannot run them beside the monolith. The cutover turns them on in payment and off in the monolith in
  one step.
- **The monolith's search_path puts `platform` first (fix).** `outbox_events`, `processed_events` and
  `idempotency_records` now exist in every extracted schema, so the old "table names are globally unique"
  premise no longer holds for those three names. An unqualified name must resolve to the monolith's platform
  copy.

**Known, pre-existing, not changed by this PR** (each is a real defect, recorded so it is not lost):
- An order cancelled while its payment intent is still PROCESSING can still capture the customer's money: the
  later `payment.succeeded` is dropped for a non-PENDING order and no refund is issued.
- A second provider event with the same second-precision timestamp as the last recorded one is stored as
  stale and never applied (ADR-012's tie rule), which can leave a captured payment to time out as FAILED.
- The provider callback HMAC covers the body but not the `{provider}` path segment, and one secret is shared
  across providers, so a signed callback can be replayed to another provider's path.
- The ApiKey authenticator's last-used throttle is dead work in payment, because the projection never records
  last-used. Either delete the throttle or record the write.
