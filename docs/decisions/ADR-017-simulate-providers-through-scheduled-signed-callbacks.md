# ADR-017: Simulate providers through scheduled, signed callbacks — never an inline call

## Status

Accepted

> This is the first module built to be **deleted from a deployment**. Every other capability is
> written to be extracted eventually; this one is written so that shipping it to production would be
> a configuration mistake rather than a code change. §1 is the whole reason the rest of the document
> is shaped the way it is.

## Context

`POST /internal/v1/provider-callbacks/{provider}` has existed since ADR-012 and has never been called
by anything but a test and a hand-signed Postman request. It is the only route that can move a
payment past `PROCESSING`, and ADR-012 named the Provider Simulator as the caller it was built for
and put it explicitly out of scope.

So the payment flow has a hole in the middle of it. Today *every* confirmed intent in this codebase
sits in `PROCESSING` until a human signs a callback by hand — which means the states that matter most
(`SUCCEEDED`, `FAILED`, `AUTHORIZED`, `REQUIRES_ACTION`) are reachable only by an operator with an
HMAC key and a terminal.

Three things already written down are waiting on this module:

- **ADR-015's mitigation.** It times a stranded `PROCESSING` payment out to `FAILED` on the strength
  of a clock, and names a reconciliation job as the thing that would catch it being wrong. That job
  does not exist because it had no input.
- **ADR-012 §5's tie-refusal.** It accepts refusing callbacks with equal `occurred_at` and names a
  provider **sequence number** as the proper fix, which needs a producer.
- **Open item 8**, the single shared callback secret.

SDD §13 specifies the module. SDD §13.2 constrains it in one sentence that governs everything below:
*the simulator does not own PayMesh payment state or ledger truth.*

## 1. The decision: a module that could be a separate service, and is written as if it already were

`com.paymesh.simulator` is a module in this deployable, like every other capability. Unlike every
other capability, it holds **no** reference of any kind to PayMesh:

- It writes no PayMesh table. Not `payment_intents`, not `payment_attempts`, not `orders`, not
  `provider_callbacks`, not `outbox_events`.
- It imports no other capability — `ModuleBoundaryTest` asserts this in **both** directions with an
  **empty allowlist**, which is stricter than any other pair in that file. Every other pair permits
  an adapter, because reading another module's data legitimately requires naming it. This one needs
  no exception at all.
- Its only influence on PayMesh is an HTTP POST of a signed body at the callback route — exactly what
  a third party's would be.
- If the simulator were deleted, every test outside its own package would still pass.

### Why this is not fastidiousness

SDD §13.6 wants the simulator independently deployable *so that network failure between it and
PayMesh is realistic*. A single shared type, a single direct service call, or a single row written
into a payment table makes that impossible — and each of those is a **one-line** temptation, which is
why the rule is enforced by a test rather than remembered.

The sharpest instance: `CallbackBody` restates the JSON contract that `ProviderCallbackRequest`
defines, and `SimulatedOutcome` restates `ProviderOutcome`'s four names. Importing either would be
shorter and would delete the boundary — the two would then be one deployable *by definition*.

**The duplication is the contract being published rather than shared**, as it would be if the
simulator read an OpenAPI document. It has a real cost — the two can drift — and that cost is paid
by `SimulatorCallbackDeliveryIntegrationTest`, which goes red when they do. A shared type would have
suppressed exactly that notification.

## 2. The scheduler is the design, and an inline POST would make the module worthless

The obvious implementation is for the create handler to POST the callback before it returns. It is
less code, it needs no table, and it would make this module pointless.

Every failure mode the simulator exists to reproduce is a property of **when and how often** a
callback is delivered:

| Failure mode | Inline? | How the dispatcher expresses it |
|---|---|---|
| Delayed callback | No | `deliver_after = now + callback_delay_ms` |
| **Lost callback** | No | The `TIMEOUT` behaviour enqueues **no row at all** |
| Duplicate callback | No | Two rows sharing one `external_event_id` and one body |
| Out-of-order callback | No | A second row whose `occurred_at` is **earlier** than a delivered one |
| Retry after failure | No | `status` stays `PENDING`, `attempts++`, `deliver_after` pushed out |

An inline call also inverts the causality. The payment's state change and the callback would share a
transaction and a thread, so a callback could not arrive before, after, or *instead of* anything —
which is the entire space of behaviour worth simulating.

The shape copies `OrderExpirySweeper` exactly, because that pattern is already reviewed:
`SimulatorCallbackDispatcher` is a `@Scheduled` bean whose body is one call and one log line, and
`DispatchProviderCallbacksService` is a plain object every test drives directly.

**`enabled: true` in `application.yaml`, `enabled: false` in `application-dev.yaml`.** Non-negotiable:
`dev` is the profile every `@SpringBootTest` runs under, and a timer POSTing callbacks at PayMesh
mid-test is the purest flake generator in this codebase. The bean is `@ConditionalOnProperty`, so
switching it off removes the timer rather than running a no-op.

## 3. Authentication: a third secret, and it is weaker on purpose

SDD §13.3 says "internal service auth" without naming a mechanism. PayMesh has no service-to-service
credential, so one had to be chosen.

| Candidate | Verdict |
|---|---|
| A merchant bearer token | **Rejected outright.** `POST /sim/v1/payments` queues a callback that will mark a payment `SUCCEEDED`. A merchant who could call it could collect their own payment — the *exact* compromise `ProviderCallbackSignatureFilter` exists to prevent, reintroduced one route over |
| Reuse `paymesh.provider.callback-secret` | **Rejected.** It works and is lazier. But one value would then both mint provider payments and sign the callbacks marking them collected, so one leak does both jobs — and it makes open item 8 strictly *harder*, because the single secret would carry two unrelated meanings and could not be split without changing both directions at once |
| `permitAll()` | **Rejected.** Anyone who can reach the port could collect any payment |

**Chosen: a dedicated shared key,** `X-PayMesh-Simulator-Key`, bound from `paymesh.simulator.api-key`,
checked by `SimulatorApiKeyFilter` — constant-time via `MessageDigest.isEqual`, one
`401 SIMULATOR_KEY_INVALID` for every failure, `@NotBlank` at startup, and a **third entry in
`DevelopmentSecretGuard.GUARDED`** so the committed development value cannot be deployed.
`/sim/v1/**` is `permitAll()` on the Spring chain for the same reason the callback route is: the
chain has no bearer token to evaluate, and the filter is the authentication.

### Why a static key here and an HMAC-over-body there

The two directions are not symmetrical, and the asymmetry is the justification rather than an
oversight:

- **Inbound**, the body *is* the money-moving claim, it arrives from outside the trust boundary, and
  a captured request can be replayed — so the body and a timestamp must be inside the signed string.
- **Outbound**, the money-moving claim is the callback the simulator later *emits*, and that one is
  HMAC-signed. A static key on a request that merely asks the provider to *start* a payment is
  proportionate.

It is weaker. It is weaker on the record, here, rather than by omission.

**Does this make per-provider secrets (open item 8) easier or harder? Easier.** The platform now has
two provider-adjacent secrets rather than one overloaded one, and the callback signing secret is read
at exactly one place — the dispatcher's sender — which is precisely where a `Map<provider, secret>`
would go.

## 4. Provider-side idempotency is the module's own, not the platform's

SDD §13.1 requires that a repeated create with the same provider-side key return the original.

`IdempotencyFilter` is **not** in this path and must not be. It keys on
`merchant + endpoint template + Idempotency-Key`, and the merchant comes from a *verified bearer
token*. A provider has no PayMesh account, no merchant and no token, so the key it would compute has
a null first component — and registering a `/sim/v1/**` route would either fail to resolve a merchant
or, worse, quietly scope every provider request to whatever tenant happened to be in the context.

`uq_provider_payments_idempotency_key` is the guard instead; the application pre-check exists only
for a friendlier answer.

**Same key + different body is `409`, not "return the original".** Real providers differ here and
returning the original would be friendlier — but the original may be for a different amount, and
answering *"your ₹19.99 payment succeeded"* to a request for ₹500 is a money-path lie. Failing closed
matches ADR-009's reasoning for the platform layer.

## 5. Failure injection: the token wins, the profile fills in

SDD §13.6 asks for deterministic test tokens; SDD §13.1 asks for latency, timeout and error
percentages. These pull in opposite directions and the resolution is stated rather than fudged:
**the token is deterministic and wins; the profile is ambient and applies only where the token asks
for nothing.**

The behaviour is **resolved once at create time and stored on the row**, so changing the profile
mid-flight cannot make a payment already in progress change its mind — a provider does not
retroactively decline something it authorized.

**Percentage-based injection is deliberately not built.** A probabilistic path in a suite that runs
on every commit is a flake generator, and the thing percentages are actually used for — "make
everything decline for a while" — is `default_behaviour` at its limit.

## Consequences

### What this closes

- Every provider-driven payment state is now reachable from outside, deterministically, with no
  hand-signed request: `SUCCEEDED`, `FAILED`, `AUTHORIZED`, `REQUIRES_ACTION`, and the stranded
  `PROCESSING` that ADR-015's sweeper exists for.
- The four callback outcomes PayMesh can answer — `APPLIED`, `DUPLICATE`, `IGNORED_STALE`,
  `IGNORED_TERMINAL` — are each provably reachable over real HTTP through the real signature filter.

### What this does NOT close, said plainly because overclaiming would be easy

- **ADR-015's reconciliation gap stays open.** `GET /sim/v1/reconciliation/{date}` produces the
  input; the *job* that compares the provider's truth to PayMesh's and repairs divergences does not
  exist. This PR removes the reason the gap could not be closed. It does not close it.
- **ADR-012's sequence number stays open.** The simulator could emit one, but nothing on the
  receiving side reads one — the field would have to be added to `payment_attempts`, the request
  record, `ProviderEvent` and the guard, all of which live in the module this one may not import.
- **Open item 8 stays open.** §3 argues this design moves it closer, not that it resolves it.

### Accepted costs

- **The HTTP call happens inside the row's transaction.** `read-timeout` is therefore also the
  longest a hung receiver can hold one row lock and one database connection, which is why it is 2s
  and the batch is small. The correct shape at scale is claim-then-send-then-ack with a lease expiry;
  for a simulator posting to localhost that is machinery for a problem it will not have.
- **Delivery is at-least-once and never exactly-once.** A callback can be POSTed, applied by PayMesh,
  and the response lost before the row is marked `DELIVERED` — in which case it is redelivered.
  That is correct: `pk_provider_callbacks` is what makes it safe, and a redelivery answering
  `DUPLICATE` is the mechanism working, not a defect.
- **The simulator's auth is weaker than the callback route's.** §3.
- **`actionUrl` points at nothing.** The 3DS flow implies a customer-facing challenge screen; there
  is none. PayMesh only stores and redacts the URL.
- **No payouts.** SDD §13.3 lists `POST /sim/v1/payouts`; payouts serve Settlement, which is Phase 2
  and has no consumer, no table and no receiver. Building it now would be scaffolding for a caller
  that does not exist.
- **No refund callbacks.** `/internal/v1/provider-callbacks` speaks only the four payment outcomes,
  so a refund callback today would retry into a `404` until it was `ABANDONED`. The refund row is the
  provider's truth and appears in the reconciliation export; the dispatcher gains a refund row type
  in the PR that builds the receiver.
