# Provider Simulator — design spec

_Written 2 August 2026, before any code. SDD §13. Branch `feat/provider-simulator`.
Migration number **V13** is reserved for this work; V14 belongs to the event relay,
V15 and V16 are unassigned._

---

## 0. What this is, and the one thing it must not become

The Provider Simulator behaves like an external card / UPI / bank provider so the payment
flow can be exercised end to end **deterministically**: success, decline, 3DS, timeout,
delayed callback, duplicate callback, out-of-order callback.

PayMesh already has the endpoint it calls. `POST /internal/v1/provider-callbacks/{provider}`
was built as exactly this seam (ADR-012, "Deliberately out of scope: the Provider
Simulator"), it is authenticated by HMAC and nothing else, and it is the only thing that can
move a payment past `PROCESSING`. This PR builds the caller.

**The governing constraint of the whole design (SDD §13.2):**

> The simulator does not own PayMesh payment state or ledger truth.

Which is enforced literally:

- It writes **no** PayMesh table. Not `payment_intents`, not `payment_attempts`, not
  `orders`, not `provider_callbacks`, not `outbox_events`, not any ledger table.
- It **imports no other capability**. Not `com.paymesh.payment`, not `com.paymesh.order`,
  not `com.paymesh.merchant`. `ModuleBoundaryTest` asserts this in both directions with an
  empty allowlist.
- Its only influence on PayMesh is an HTTP POST of a signed body to the public-ish callback
  route, exactly as a third-party provider's would be. If the simulator were deleted, every
  PayMesh test except this module's would still pass.

This is not fastidiousness. SDD §13.6 wants the simulator **independently deployable** so
that network failure between it and PayMesh is realistic. A single shared type, a single
direct service call, or a single row written into a payment table makes that impossible, and
each of those is a one-line temptation.

---

## 1. Package, layers, wiring

`com.paymesh.simulator`, four layers, dependency inward, per ADR-002:

```
com.paymesh.simulator
├── api             SimulatorController, request/response records, SimulatorExceptionHandler
├── application     use-case services, commands, repository interfaces, business exceptions
├── domain          SimulatedPayment, SimulatedRefund, OutboundCallback, ids, enums
└── infrastructure  config (SimulatorConfiguration + properties), persistence/jpa,
                    http (the outbound sender), security (the API-key filter),
                    schedule (the dispatcher)
```

Beans are wired by hand in `SimulatorConfiguration`. Application/domain classes are plain
`final` classes with no `@Service`/`@Component`/`@Repository`. Only the controller, the
`@RestControllerAdvice`, the `@Configuration` and the `@Scheduled` dispatcher carry Spring
annotations. Domain aggregates and JPA entities are separate types with hand-written mappers
(ADR-004).

### 1.1 Identifiers

The simulator mints the **provider's** identifiers, not PayMesh's, so no prefix may collide
with `mrc_ usr_ cus_ ord_ pi_ pay_ evt_ ref_ stl_ whe_`.

| Id | Prefix | Value object? | Why |
|---|---|---|---|
| Simulated payment | `sim_pay_` | **Yes** — `SimulatedPaymentId` | Public: returned in responses, accepted in `POST /sim/v1/payments/{id}/capture`, and it becomes `payment_attempts.provider_reference` inside PayMesh |
| Simulated refund | `sim_ref_` | **Yes** — `SimulatedRefundId` | Public: returned in responses |
| Outbound callback row | `sim_cb_` | **No**, a plain minted `String` | Never public. It is never accepted from a caller and never parsed back, so a validating constructor would validate a value only this module has ever produced |
| External event id (in the callback body) | `sim_evt_` | **No**, a plain minted `String` | Same — it leaves as JSON and lands in PayMesh's `provider_callbacks.external_event_id`, but nothing ever hands it back to the simulator |

`sim_pay_` and `sim_evt_` are the strings the existing payment tests and Postman collection
already use for provider references and event ids. That is deliberate continuity, not
coincidence.

---

## 2. Schema (V13)

Four tables. Every one of them is the **provider's** truth, and none of them references a
PayMesh table — there is not a single foreign key out of this migration, because a foreign
key to `payment_intents` would be the coupling §0 forbids expressed in SQL.

### 2.1 `provider_payments`

| Column | Type | Notes |
|---|---|---|
| `provider_payment_id` | `VARCHAR(50)` PK | `sim_pay_<uuid>` |
| `idempotency_key` | `VARCHAR(120)` NOT NULL, **UNIQUE** | Provider-side idempotency. §5 |
| `request_hash` | `CHAR(64)` NOT NULL | SHA-256 of the canonical request tuple. Same key + different request → `409` |
| `callback_reference` | `VARCHAR(60)` NOT NULL | **The caller's own reference, echoed into every callback. Not a foreign key, not state.** §2.5 |
| `method` | `VARCHAR(20)` NOT NULL | `CARD`/`UPI`/`WALLET`/`BANK`, CHECK-constrained |
| `token` | `VARCHAR(60)` NOT NULL | The deterministic test token. SDD §13.6 |
| `behaviour` | `VARCHAR(30)` NOT NULL | Resolved once at create time from the token or the ambient profile, then stored. §6 |
| `amount_minor` | `BIGINT` NOT NULL, `CHECK > 0` | Positive integer minor units |
| `currency` | `CHAR(3)` NOT NULL | |
| `capture_method` | `VARCHAR(10)` NOT NULL | `AUTOMATIC`/`MANUAL` |
| `status` | `VARCHAR(20)` NOT NULL | `PENDING`, `AUTHORIZED`, `CAPTURED`, `DECLINED`, `REQUIRES_ACTION`, `TIMED_OUT` |
| `captured_amount_minor` | `BIGINT` NOT NULL DEFAULT 0 | `CHECK (captured_amount_minor <= amount_minor)` |
| `refunded_amount_minor` | `BIGINT` NOT NULL DEFAULT 0 | `CHECK (refunded_amount_minor <= captured_amount_minor)` — **the database refuses an over-refund, not the application** |
| `failure_code`, `failure_message` | `VARCHAR(60)`, `VARCHAR(500)` | |
| `created_at`, `updated_at` | `TIMESTAMPTZ` NOT NULL | |

`uq_provider_payments_idempotency_key` is **global, not tenant-scoped**, and that is correct
here for the same structural reason `pk_provider_callbacks` is not merchant-leading: a
provider serves one API credential and has no tenant. There is no merchant column on any
table in this migration, because the simulator has never been told a merchant exists.

Index: `idx_provider_payments_created_at (created_at)` — the reconciliation export's only
access pattern beyond the primary key.

### 2.2 `provider_refunds`

`provider_refund_id` PK (`sim_ref_<uuid>`), `provider_payment_id` FK → `provider_payments`,
`idempotency_key` UNIQUE, `request_hash`, `amount_minor` (`CHECK > 0`), `status`
(`SUCCEEDED`/`FAILED`), `failure_code`, `failure_message`, `created_at`, `updated_at`.

This FK is *within* the simulator, so it is fine and wanted.

### 2.3 `provider_outbound_callbacks`

**Named differently from PayMesh's inbound `provider_callbacks` (V10) on purpose.** Two
tables with one name on opposite sides of a boundary is a trap: a support query, a
reconciliation job or a reviewer would read one believing it was the other, and the two
disagree by design — a row here is what the provider *intends* to say; a row there is what
PayMesh *did* about what it heard.

| Column | Type | Notes |
|---|---|---|
| `outbound_callback_id` | `VARCHAR(60)` PK | `sim_cb_<uuid>`. The row's identity |
| `external_event_id` | `VARCHAR(120)` NOT NULL, **deliberately NOT unique** | The provider's event id, in the body. **Two rows sharing one value IS the duplicate-callback failure mode.** A unique constraint here would make that scenario unbuildable, which is why the note is in the migration and not only here |
| `callback_reference` | `VARCHAR(60)` NOT NULL | Denormalised for the operator query "what have I said about this payment" |
| `provider_payment_id` | `VARCHAR(50)` NOT NULL FK | |
| `outcome` | `VARCHAR(20)` NOT NULL | `AUTHORIZED`/`SUCCEEDED`/`FAILED`/`REQUIRES_ACTION` — **the receiver's vocabulary, restated here** (§7) |
| `occurred_at` | `TIMESTAMPTZ` NOT NULL | **The provider's clock, and the value PayMesh's monotonic ordering guard compares.** Setting it earlier than a already-delivered event is how out-of-order is expressed |
| `deliver_after` | `TIMESTAMPTZ` NOT NULL | The dispatcher will not pick the row before this. **This is the delayed-callback knob** |
| `body` | `TEXT` NOT NULL | **The exact JSON bytes to send, serialized once at enqueue time.** §4.2 |
| `status` | `VARCHAR(20)` NOT NULL | `PENDING`, `DELIVERED`, `ABANDONED` |
| `attempts` | `INT` NOT NULL DEFAULT 0 | |
| `last_attempt_at` | `TIMESTAMPTZ` | |
| `last_response_status` | `INT` | HTTP status PayMesh answered |
| `last_response_outcome` | `VARCHAR(32)` | `APPLIED`/`DUPLICATE`/`IGNORED_STALE`/`IGNORED_TERMINAL`, read out of the response body. **The observable proof a test asserts on** |
| `created_at`, `updated_at` | `TIMESTAMPTZ` NOT NULL | |

Index: `idx_provider_outbound_callbacks_due (status, deliver_after)` — the dispatcher's only
query.

### 2.4 `provider_failure_profile`

One row, and the database says so: `profile_id VARCHAR(20) PRIMARY KEY CHECK (profile_id =
'DEFAULT')`. A singleton enforced by a CHECK rather than by convention, because "there is
only ever one row" is precisely the kind of convention that stops being true silently.

| Column | Type | Notes |
|---|---|---|
| `profile_id` | `VARCHAR(20)` PK, `CHECK = 'DEFAULT'` | |
| `default_behaviour` | `VARCHAR(30)` NOT NULL DEFAULT `'SUCCEED'` | What a payment gets when its token names nothing |
| `callback_delay_ms` | `INT` NOT NULL DEFAULT 0, `CHECK >= 0` | Added to `deliver_after` on every enqueued callback. The latency injection of SDD §13.1 |
| `updated_at` | `TIMESTAMPTZ` NOT NULL | |

The row is **seeded by the migration**, so the profile always exists and no code has to
handle its absence.

### 2.5 `callback_reference` is an address, not state

The create request carries a `callbackReference` — in practice PayMesh's payment intent id,
but the simulator neither knows nor cares. It is stored, echoed into the `paymentIntentId`
field of every callback body, and never interpreted. A real provider models exactly this as
a merchant reference passthrough.

Calling the column `payment_intent_id` would have been shorter and would have been a lie: it
would suggest the simulator knows what a payment intent is and, worse, would read to the
next implementer as a natural place to hang a foreign key.

---

## 3. API contracts

All under `/sim/v1/**`. **This is not the merchant API** and must not appear in merchant
docs or in any merchant folder of the Postman collection.

| Method and path | Purpose |
|---|---|
| `POST /sim/v1/payments` | Create a simulated provider payment (authorize) |
| `POST /sim/v1/payments/{id}/capture` | Capture an authorization |
| `POST /sim/v1/refunds` | Start a simulated refund |
| `GET  /sim/v1/reconciliation/{date}` | Export the provider's own truth for that day |
| `POST /sim/v1/failure-profile` | Configure failure injection |

**`POST /sim/v1/payouts` is deliberately not built.** SDD §13.3 lists it; payouts serve
Settlement, which is Phase 2, and there is no Settlement Service, no `provider_payouts`
consumer and no payout callback receiver. Building it now would be scaffolding for a caller
that does not exist. `provider_payouts` (SDD §13.4) is likewise not created.

### 3.1 Authentication for `/sim/v1/**`, and why it is a third secret

SDD §13.3 says "Internal service auth" / "Admin test auth". PayMesh has no service-to-service
credential yet, so one has to be chosen. Three candidates were considered:

1. **A merchant bearer token — rejected outright.** `POST /sim/v1/payments` enqueues a
   callback that will mark a payment `SUCCEEDED`. A merchant who could call it could collect
   their own payment. That is the *exact* compromise `ProviderCallbackSignatureFilter`'s
   javadoc says the design exists to prevent, reintroduced one route over.
2. **Reuse `paymesh.provider.callback-secret` — rejected.** It would work, and it is the
   lazier option, but it makes one value both the thing that mints provider payments and the
   thing that signs the callbacks marking them collected. One leak then does both jobs, and
   it makes open item #8 (per-provider secrets) strictly harder: the single secret would then
   have two unrelated meanings and could not be split without changing both directions at
   once.
3. **`permitAll()` — rejected.** Anyone who can reach the port could collect any payment.

**Chosen: a dedicated shared key, `X-PayMesh-Simulator-Key`,** bound from
`paymesh.simulator.api-key`, checked by `SimulatorApiKeyFilter` — constant-time via
`MessageDigest.isEqual`, one `401 SIMULATOR_KEY_INVALID` for every failure, `@NotBlank` at
startup, and a third entry in `DevelopmentSecretGuard.GUARDED` so the committed development
value cannot be deployed. `/sim/v1/**` is `permitAll()` on the Spring chain for the same
reason the callback route is: the chain has no bearer token to evaluate, and the filter is
the authentication.

**Why a static key here and an HMAC-over-body there.** The two directions are not
symmetrical. Inbound, the body *is* the money-moving claim, arrives from outside the trust
boundary, and can be captured and replayed — so it needs the body and a timestamp inside the
signed string. Outbound, the money-moving claim is the callback the simulator later *emits*,
and that is HMAC-signed. A static key on the request that merely asks the provider to start
a payment is proportionate. It is written down here so that "the simulator's auth is weaker"
is a decision on record rather than something discovered.

**Does this make per-provider secrets (open item #8) easier or harder? Easier.** The
simulator now holds its own credential, so the platform already has two provider-adjacent
secrets rather than one overloaded one; and the callback signing secret is read at exactly
one place — the dispatcher's sender — which is precisely where a `Map<provider, secret>`
lookup would go.

### 3.2 `POST /sim/v1/payments`

Request: `idempotencyKey`, `callbackReference`, `method`, `token`, `amountMinor`,
`currency`, `captureMethod`. Boundary validation on the request record; the domain owns
normalization and invariants.

Response `201`: `providerPaymentId`, `status`, `behaviour`, `amountMinor`, `currency`,
`capturedAmountMinor`, `createdAt`. A repeat with the same key returns **`200`** and the
original payment — `200` rather than `201` so a caller can tell a replay from a create
without diffing bodies.

### 3.3 `POST /sim/v1/payments/{id}/capture`

Request: `idempotencyKey`, `amountMinor` (optional; absent means the full authorized
amount). Legal only from `AUTHORIZED`; anything else is `409 SIMULATED_PAYMENT_NOT_CAPTURABLE`.
Captures the amount and enqueues a `SUCCEEDED` callback.

### 3.4 `POST /sim/v1/refunds`

Request: `idempotencyKey`, `providerPaymentId`, `amountMinor`. Refuses an amount exceeding
`captured - refunded`; the application checks it under a row lock for a readable `422`, and
the `CHECK (refunded_amount_minor <= captured_amount_minor)` is what actually guarantees it.

**No refund callback is enqueued, and that is deliberate.** `/internal/v1/provider-callbacks`
speaks only the four payment outcomes; there is no refund receiver, because the Refund
capability lands later. Enqueuing a callback with nowhere to land would be a permanently
`ABANDONED` row and a retry loop against a `404`. The refund row is the provider's truth and
appears in the reconciliation export today; the dispatcher gains a refund row type in the PR
that builds the receiver. This endpoint exists now so Refund is not blocked on this module.

### 3.5 `GET /sim/v1/reconciliation/{date}`

`date` is an ISO `YYYY-MM-DD`, interpreted in UTC. Returns the provider's own truth for that
day: every `provider_payment` and `provider_refund` created in it, with final status and
amounts, plus counts and totals per status.

JSON, not CSV. The house content type is JSON everywhere, and the consumer that does not
exist yet is a reconciliation *job*, not a spreadsheet.

**What this does and does not close** — stated plainly because the temptation to overclaim
is real:

- ADR-015 leans on a reconciliation job as the mitigation for timing a stranded `PROCESSING`
  payment out to `FAILED`. **This PR does not close that gap. It removes the reason the gap
  could not be closed.** The job still does not exist; it now has an input.
- ADR-012 §5 accepts refusing tied `occurred_at` values, and names a provider **sequence
  number** as the proper fix. **This PR does not close that either.** The simulator could
  emit a sequence number, but nothing on the receiving side reads one — the field would have
  to be added to `payment_attempts`, the request record, `ProviderEvent` and the guard, all
  of which live in `com.paymesh.payment`. That is the payment module's PR, and doing it from
  here would mean editing the module this one is forbidden to import. What this PR
  contributes is the producer that ADR-012 said did not exist.

### 3.6 `POST /sim/v1/failure-profile`

Request: `defaultBehaviour`, `callbackDelayMs`. Upserts the single row, returns `200` with
the profile. Deliberately not idempotency-keyed: it is a last-write-wins configuration
setting, and a replayed configuration change is the same configuration.

---

## 4. Callback delivery: a scheduled dispatcher, never an inline call

### 4.1 Why the scheduler is the whole design

An inline `POST` from inside the create handler would be simpler and would make this module
worthless. Every failure mode the simulator exists to reproduce is a property of *when and
how often* the callback is delivered:

| Failure mode | Expressible inline? | How the dispatcher expresses it |
|---|---|---|
| Delayed callback | No | `deliver_after = now + callback_delay_ms` |
| Lost callback | No | The `TIMEOUT` behaviour enqueues **no row at all** |
| Duplicate callback | No | Two rows sharing one `external_event_id` and one body |
| Out-of-order callback | No | A second row whose `occurred_at` is **earlier** than an already-delivered one |
| Retry after a failed delivery | No | `status` stays `PENDING`, `attempts++`, `deliver_after` pushed out |

An inline call also inverts the causality: the payment's state change and the callback would
share a transaction and a thread, so a callback could not arrive before, after, or instead of
anything.

The shape copies `OrderExpirySweeper` exactly, because that pattern is already reviewed:

- `SimulatorCallbackDispatcher` — a `@Scheduled(fixedDelayString=..., initialDelayString=...)`
  bean whose body is **one call and one log line**. `fixedDelay`, not `fixedRate`, so a slow
  batch is never re-entered.
- `SimulatorDispatchProperties` — `enabled`, `batchSize`, `maxAttempts`, `retryDelay`. The
  `interval` is resolved by Spring from the environment directly and deliberately not bound
  into the record, per `OrderExpiryProperties`' note.
- `@ConditionalOnProperty(..., matchIfMissing = true)` on the bean, so switching it off
  removes the timer rather than running a no-op.
- **`enabled: true` in `application.yaml`, `enabled: false` in `application-dev.yaml`.**
  Non-negotiable: `dev` is the profile every `@SpringBootTest` runs under, and a timer
  posting callbacks at PayMesh mid-test is exactly the flake generator that file's header
  block exists to prevent. `DispatchProviderCallbacksService` is a plain bean that exists
  regardless, and every test drives `dispatch()` directly.

### 4.2 Signing: the exact bytes, once

`X-PayMesh-Signature: t=<unix-seconds>,v1=<hex HMAC-SHA256 of (t + "." + rawBody)>`, using
`paymesh.provider.callback-secret`, within the receiver's ±300s tolerance.
`ProviderCallbackSignatureFilter` is the authority and this must match it exactly; a near
miss produces a `401` that reads like a bug in the receiver.

Two mechanics carry the correctness:

1. **The body is serialized once, at enqueue time, and stored as `TEXT`.** Not `JSONB` — a
   `JSONB` round trip normalises key order and whitespace, so the bytes read back would not
   be the bytes that were serialized. Storing the string means the row *is* the payload.
2. **The dispatcher signs the stored string and posts that same string**, as
   `application/json` with an explicit `StandardCharsets.UTF_8`, never a re-serialized
   object. The signature covers the bytes on the wire because they are the same object in
   memory. Re-serializing after signing is the single most likely way to build this wrong,
   which is why the sabotage list has an entry for it.

The timestamp is read from the injected `Clock` at **delivery** time, not enqueue time — a
callback deliberately delayed by ten minutes must not arrive carrying a ten-minute-old
signature and be refused as stale by the freshness window. `occurred_at` (the provider's
event clock, which the ordering guard compares) and `t` (the signature's freshness stamp)
are different facts and are produced at different moments. Conflating them makes every
delayed-callback test fail with a `401`.

### 4.3 Transaction boundaries

| Operation | Boundary |
|---|---|
| Create payment | One transaction: the `provider_payments` row **and** its `provider_outbound_callbacks` row(s). Either the provider took the payment and intends to report it, or neither happened |
| Capture | One transaction: the payment's status/amount change and the `SUCCEEDED` callback row |
| Refund | One transaction: the `provider_refunds` row and the payment's `refunded_amount_minor` |
| **Dispatch one callback** | **One transaction per row**, and the HTTP call is inside it |
| Failure profile upsert | One statement |

The dispatcher takes each due row with `SELECT ... FOR UPDATE SKIP LOCKED`, POSTs, records
the result, commits. `SKIP LOCKED` means a second dispatcher (or a second instance) takes
different rows rather than blocking.

**Accepted cost:** the HTTP call happens while the row lock is held, so a hung receiver holds
one row lock and one connection for the client's read timeout. That is why the read timeout
is short (2s) and the batch is small. The correct shape at scale is claim-then-send-then-ack,
which needs a third state and a lease expiry; for a simulator delivering to localhost that is
machinery for a problem this will not have. Named in ADR-017 under accepted costs, not hidden.

Delivery is **at-least-once and never exactly-once**: a callback can be POSTed, applied by
PayMesh, and the response lost before the row is marked `DELIVERED`, in which case it is
redelivered. That is correct and is the platform-wide rule — PayMesh's `pk_provider_callbacks`
is what makes it safe, and a redelivery answering `DUPLICATE` is the mechanism working.

### 4.4 Response handling

| Received | Action |
|---|---|
| `2xx` | `DELIVERED`, store the status and the `outcome` from the body |
| `404` | Stay `PENDING`, `attempts++`, `deliver_after += retryDelay`. **The retry is wanted** — ADR-012 §7 says a `404` most likely means the callback overtook the transaction that created the intent |
| any other non-2xx, or a transport failure | Same retry path |
| `attempts >= maxAttempts` | `ABANDONED`, and logged at WARN. A provider gives up eventually; a row that retried forever would be an infinite loop against a `401` |

---

## 5. Provider-side idempotency, and why it is not the platform's

SDD §13.1 requires it: a repeated create with the same provider-side key returns the original
simulated payment and does not create a second one.

**This is the simulator's own mechanism and deliberately not `IdempotencyFilter`.** That
layer keys on `merchant + endpoint template + Idempotency-Key`, and the merchant comes from a
*verified bearer token*, which is why the filter is ordered after Spring Security. A provider
has no PayMesh account, no merchant and no token, so the key it would compute has a null
first component. Registering a `/sim/v1/**` route in `IdempotentRoutes` would either fail to
resolve a merchant or, worse, quietly scope every provider request to whatever tenant
happened to be in the context. The same argument is already written into
`ProviderCallbackController`'s javadoc for the inbound direction; this is its mirror image.

The mechanism instead:

- `uq_provider_payments_idempotency_key` on `provider_payments`, and the same on
  `provider_refunds`. **The unique constraint is the guard; the application pre-check exists
  only for a friendlier answer** — the house pattern, and the integration test proves it by
  racing two creates on one key and asserting exactly one row.
- On create: look up by key. Found and the request hash matches → return the original,
  `200`. Found and the hash differs → `409 SIMULATOR_IDEMPOTENCY_KEY_REUSED`. Not found →
  insert; a concurrent loser gets the unique violation, re-reads, and returns the winner's
  row.
- **Same key + different body is `409`, not "return the original".** Real providers differ
  here, and returning the original would be the friendlier choice — but the original may be
  for a different amount, and answering "your ₹5000 payment succeeded" to a request for
  ₹50000 is a money-path lie. Failing closed matches ADR-009's reasoning for the platform
  layer.
- `request_hash` is SHA-256 over the canonical tuple
  `callbackReference|method|token|amountMinor|currency|captureMethod`, not over raw bytes.
  Unlike the platform filter — which hashes raw bytes precisely to avoid parsing
  attacker-controlled JSON before the dedup decision — the body here is already parsed and
  validated by the time the service sees it, and the caller is PayMesh itself.

---

## 6. Failure injection: deterministic tokens first, ambient profile second

SDD §13.6 asks for **deterministic test tokens**. SDD §13.1 asks for **latency, timeout and
error percentages**. These pull in opposite directions, and the resolution is stated rather
than fudged:

**The token is deterministic and wins. The profile is ambient and applies only where the
token asks for nothing.**

| Token | Behaviour | What PayMesh sees |
|---|---|---|
| `tok_sim_success` | `SUCCEED` | `AUTOMATIC`: one `SUCCEEDED` callback → intent `SUCCEEDED`. `MANUAL`: one `AUTHORIZED` callback → intent `AUTHORIZED`, awaiting the merchant's capture |
| `tok_sim_decline` | `DECLINE` | One `FAILED` callback, `do_not_honour` → intent `FAILED` |
| `tok_sim_3ds` | `REQUIRE_ACTION` | One `REQUIRES_ACTION` callback with an `actionUrl` carrying a challenge token in its query string (which PayMesh must redact) |
| `tok_sim_timeout` | `TIMEOUT` | **No callback row at all.** The payment sits `TIMED_OUT` at the provider and the intent strands in `PROCESSING` — the lost-callback case, and precisely what ADR-015's sweeper exists for |
| `tok_sim_duplicate` | `DUPLICATE_CALLBACK` | **Two rows, one `external_event_id`, identical bodies.** First → `APPLIED`, second → `DUPLICATE` |
| `tok_sim_stale` | `STALE_CALLBACK` | **Two rows, distinct event ids, the second carrying an *earlier* `occurred_at`.** First → `APPLIED`, second → `IGNORED_STALE` |

An unrecognised or absent token falls through to `provider_failure_profile.default_behaviour`.
The behaviour is **resolved once at create time and stored on the row**, so changing the
profile mid-flight cannot make a payment already in progress change its mind — a provider
does not retroactively decline something it authorized.

### 6.1 Why `STALE_CALLBACK` is shaped the way it is

Producing `IGNORED_STALE` from outside is less obvious than it looks. PayMesh judges
staleness **before** the state machine (`RecordProviderCallbackService.judge`), so a second
event whose `occurred_at` is not strictly after `payment_attempts.last_provider_event_at` is
`IGNORED_STALE` regardless of what state the intent is in. If the checks ran the other way
round the second event would be `IGNORED_TERMINAL` instead, since every provider outcome
leaves `PROCESSING`.

So: two `SUCCEEDED` events, distinct event ids, the second stamped **earlier**. The first is
applied and writes `last_provider_event_at`; the second is refused as stale. That is a
faithful reproduction of a provider whose delivery queue reordered, and it needs no merchant
action in between.

### 6.2 Percentage-based injection is deliberately not built

SDD §13.1's "timeout and error percentages" is not implemented, and this is a knowing
deviation. A probabilistic path in a suite that runs on every commit is a flake generator,
and the thing percentages are actually used for — "make everything decline for a while" — is
`default_behaviour` at its limit. `callback_delay_ms` covers the latency half, which is the
part that is deterministic and therefore useful. If a soak test ever needs a real
distribution, the column is `decline_percent` and the seed belongs in the request.

---

## 7. The wire contract is restated, not imported

The callback body must match `ProviderCallbackRequest` exactly:

```json
{
  "eventId": "sim_evt_<uuid>",
  "occurredAt": "2026-08-02T11:00:00Z",
  "paymentIntentId": "<the stored callbackReference>",
  "providerReference": "sim_pay_<uuid>",
  "outcome": "SUCCEEDED",
  "authorizedAmountMinor": 1999,
  "capturedAmountMinor": 1999,
  "failureCode": null,
  "failureMessage": null,
  "actionUrl": null
}
```

Two contract details that are easy to get wrong and produce a silent `IGNORED_TERMINAL`
rather than an error:

- **`SUCCEEDED` must carry `capturedAmountMinor` equal to the intent's amount, and
  `AUTHORIZED` must carry `authorizedAmountMinor` equal to it.** `RecordProviderCallbackService`
  refuses a claimed amount the intent does not authorize and records `IGNORED_TERMINAL` — a
  provider does not get to change what is owed (SDD §12.3). A simulator that sent the wrong
  field, or omitted it, would look like it was working and never move a payment.
- **`FAILED` and `REQUIRES_ACTION` must carry no amount.** They are not checked, but sending
  one is noise in a durable audit record.

`SimulatedOutcome` is the simulator's own enum with the same four names, and
`SimulatorCallbackBody` is its own record. **Importing `ProviderOutcome` or
`ProviderCallbackRequest` from `com.paymesh.payment` would be one line and would delete the
boundary** — the two would then be one deployable by definition, contradicting SDD §13.6.
The duplication is the contract being *published* rather than *shared*, exactly as it would
be if the simulator were a separate service reading an OpenAPI document. If PayMesh changes
the contract, the simulator's integration test goes red, which is the notification a shared
type would have suppressed.

---

## 8. Testing

Project conventions apply: plain JUnit for domain and application, `@SpringBootTest` +
`MockMvc` for the API layer, Testcontainers for integration, `@ActiveProfiles("dev")`, test
names that state behaviour.

| Class | Kind | Covers |
|---|---|---|
| `SimulatedPaymentIdTest`, `SimulatedRefundIdTest` | plain JUnit | Prefix and UUID validation |
| `SimulatedPaymentTest` | plain JUnit | Authorize/capture/decline transitions, over-capture, over-refund |
| `SimulatedBehaviourTest` | plain JUnit | Token → behaviour resolution, ambient fallback |
| `SimulatorConfigurationTest` | `@SpringBootTest` | Every bean is wired; the dispatcher bean is **absent** under `dev` |
| `SimulatorApiTest` | `@SpringBootTest` + MockMvc | The API key, idempotency replay and reuse, capture, refund, the reconciliation export, the failure profile |
| `SimulatorCallbackDeliveryIntegrationTest` | `@SpringBootTest(RANDOM_PORT)` + Testcontainers | **The one that matters**: the simulator's callbacks going over real HTTP through the real `ProviderCallbackSignatureFilter` into the real `RecordProviderCallbackService` |
| `ModuleBoundaryTest` (extended) | plain JUnit | The simulator imports no capability; no capability imports the simulator |

`SimulatorCallbackDeliveryIntegrationTest` runs against a real embedded server so the bytes
actually cross a socket. It constructs `DispatchProviderCallbacksService` with an
`HttpCallbackSender` pointed at `http://localhost:{port}`, exactly as
`ProviderCallbackIntegrationTest` constructs the production service with one collaborator
swapped. `CallbackSender` is an interface with one implementation — which the house rules
otherwise discourage — because the application layer must not do HTTP and because that seam
is what makes this test possible at all.

### 8.1 Invariants proved by sabotage

Every one of these is verified by breaking the implementation and confirming the test goes
red. A green assertion that has never failed is not evidence; a sabotage that stays green
means the sabotage was unfaithful, and that gets said rather than papered over.

| Invariant | Sabotage that must turn it red |
|---|---|
| A callback this simulator signs is accepted by the real filter, and drives an intent to `SUCCEEDED` | Sign `body` alone instead of `t + "." + body` |
| The signature covers the bytes actually sent | Re-serialize the body after signing (append a space) |
| The signature timestamp is taken at delivery, not enqueue | Sign with the enqueue time on a delayed callback |
| Provider-side idempotency: same key twice → one row | Drop `uq_provider_payments_idempotency_key` |
| Duplicate callbacks produce `DUPLICATE` | Mint a fresh `external_event_id` for the second row |
| Stale callbacks produce `IGNORED_STALE` | Stamp the second row's `occurred_at` later instead of earlier |
| `TIMEOUT` enqueues nothing | Enqueue a `SUCCEEDED` callback for it anyway |
| The dispatcher is off under `dev` | Set `enabled: true` in `application-dev.yaml` |
| Create is one transaction | Remove the `TransactionTemplate` wrap and fail the callback insert |

---

## 9. Deliberately not built

- **`POST /sim/v1/payouts` and `provider_payouts`.** Settlement is Phase 2. §3.
- **Refund callbacks.** No receiver exists. §3.4.
- **Percentage-based failure injection.** §6.2.
- **A provider sequence number.** The simulator could emit one; nothing reads it, and the
  reader lives in the module this one may not import. §3.5.
- **The reconciliation *job*.** This PR produces the file; consuming it and repairing
  divergences is ADR-015's open work. §3.5.
- **Per-provider signing secrets.** Open item #8 stays open; §3.1 argues this design moves it
  closer rather than further.
- **A checkout page.** SDD's 3DS flow implies a customer-facing challenge screen. The
  `actionUrl` points at a URL that serves nothing; PayMesh only stores and redacts it.
- **Independent deployment.** The simulator is a module in the monolith like everything else.
  It is *built* so it could be extracted — no shared types, no shared tables, HTTP only — but
  extracting it is not this PR.

---

## 10. Documentation deliverables

- ADR-017, `simulate-providers-through-scheduled-signed-callbacks`, at the depth of the
  existing ADRs: rejected alternatives and accepted costs, not only the decision.
- A Postman folder driving a payment to `SUCCEEDED` **through the simulator** rather than a
  hand-signed callback, plus a decline and a duplicate-callback case. Existing folders run
  top to bottom sharing variables and must not break.
- `docs/project-status.md`, `docs/project-walkthrough.md` (§3.6 and §6.1) and `README.md`:
  Provider Simulator moves from "not started" to built. Edits scoped to simulator facts only
   — a sibling branch owns the outbox-relay prose in the same files.
