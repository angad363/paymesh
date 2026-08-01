# ADR-012: Deduplicate and order provider callbacks with three independent mechanisms

## Status

Accepted

> Number reserved by the payment capability design (§6) for this PR, which is why ADR-013 was
> written first and says so.

## Context

A provider callback is the only thing that can move a payment past `PROCESSING`. It is therefore the
most dangerous request PayMesh accepts: it marks money collected, it arrives from outside, and it
carries no merchant credential.

Callbacks misbehave in three ways, and the three are not variations of one problem.

1. **Duplicated.** Delivery is at-least-once by construction. A provider that does not see a `2xx`
   retries, and it retries whether the response was lost or never sent. Applying a `SUCCEEDED` twice
   is a payment recorded twice.
2. **Out of order.** Retries, queues and clock differences mean event *n+1* can arrive before event
   *n*. A payment that moves backwards is worse than one that never moves: `SUCCEEDED` reverting to
   `PROCESSING` is a collection PayMesh has forgotten about.
3. **Late.** The provider succeeded and the callback never arrived at all. The payment sits in
   `PROCESSING` — the one state with no local exit (ADR-011 §5) — and strands its order.

The governing invariant is that committed money movement must never be lost, silently duplicated, or
become unauditable. All three failures attack it from a different side.

Two constraints shape every answer below:

- **A provider retries on any non-2xx.** So a refusal cannot be a `409`. Answering a superseded event
  with a conflict produces an infinite retry loop against a payment that is already finished — a
  self-inflicted outage that reads, in the logs, as a provider problem.
- **There is no merchant.** The endpoint has no bearer token, so no tenant is supplied. Everything
  about the request is either derived from a row PayMesh already owns or is not trusted at all.

## Decision

### 1. Three mechanisms. None subsumes another

| Failure | Mechanism | Where it lives |
|---|---|---|
| Duplicated | `pk_provider_callbacks`, inserted **inside** the state change's transaction | `V10`, `JpaProviderCallbackRepository` |
| Out of order | The intent's state machine **and** a monotonic provider-event clock | `PaymentIntent`, `payment_attempts.last_provider_event_at` |
| Concurrent and different | `SELECT … FOR UPDATE` on the intent row, taken **before** the insert | `findForProviderCallbackForUpdate` |
| Late | Out of scope — but the transition logic sits in an application service so a reconciliation job can reuse it | `RecordProviderCallbackService` |

Removing any one of them is not caught by the others. The tests in
`ProviderCallbackIntegrationTest` name, per test, the sabotage that must turn it red.

### 2. The deduplication key is `(provider, external_event_id)` and is **not merchant-leading**

Every other uniqueness rule in PayMesh leads with `merchant_id`, because every other one scopes data
a merchant supplied. This one does not. `external_event_id` is the *provider's* id, it is
provider-global, and the merchant here is **derived** from the intent the callback names rather than
supplied by the caller.

**Adding `merchant_id` to this key would let a single provider event be processed once per merchant
it can be resolved against** — which is the exact duplicate the constraint exists to prevent, and a
duplicate that moves money. An implementer "fixing" this to match the house style reopens the hole,
which is why the reasoning is written into `V10` itself and not only here: the next reader is looking
at the SQL file, not at a design document nobody reopens.

`refusesTwoRawCallbackRowsSharingAProviderAndEventId` pins the shape directly — its second row
differs from the first *only* in merchant and intent, and it is still refused.

### 3. The callback row is inserted **inside** the transaction, and the blocking behaviour is
load-bearing

The handler inserts `provider_callbacks` in the same transaction as the state change. A duplicate
loses on the primary key, the transaction rolls back, and **nothing happened**: no transition, no
`payment_state_history` row, no outbox event. The response is `200 {"outcome": "DUPLICATE"}`.

**This is the part a reviewer talks themselves out of.** Because the insert is inside the
transaction, a concurrent duplicate does not fail immediately: PostgreSQL blocks the second inserter
on the index entry until the first transaction commits or rolls back.

- If the first **commits**, the second gets its unique violation and correctly no-ops.
- If the first **rolls back**, the second's insert succeeds and it correctly applies the event.

That second case is precisely why an in-transaction insert cannot swallow an event that was never
actually processed. Reasoning about the insert without the blocking step produces a confident,
wrong conclusion that there is a lost-event bug. There is not, and
`appliesTheSecondDeliveryWhenAConcurrentDuplicateRollsBack` is the test that says so.

Contrast the idempotency layer (ADR-009), which commits its record *before* the handler runs. That is
correct there and wrong here. There, an orphaned record is recoverable by the caller with a new key.
Here, if the callback row committed separately and the transition then failed, the provider's event
would be permanently swallowed, with the payment left in `PROCESSING` and no way to replay it. **One
transaction, one commit.**

Two mechanical consequences, both easy to undo by accident:

- The insert is **flushed**, not left for commit. Left to commit time the collision would happen
  after the transition was written, and a concurrent duplicate would not block where this argument
  says it blocks.
- The entity implements `Persistable.isNew() == true`. Spring Data's default for an
  application-assigned id with no `@Version` is *merge* — a SELECT, then an INSERT **or an UPDATE** —
  so a duplicate would quietly rewrite the existing row, never violate the key, and still answer
  `APPLIED`. The outbox reached the same fork and took the other branch (`@Immutable`, so a re-append
  is a silent no-op); that is right there and wrong here, because here the collision is the signal.

### 4. Out of order is answered twice, because one answer is not enough

**The state machine.** All four provider outcomes are legal only from `PROCESSING`. Terminal states
absorb: a `PROCESSING`-era callback arriving after the intent reached `SUCCEEDED`, `FAILED` or
`CANCELLED` is refused. So is `AUTHORIZED → SUCCEEDED` — that is a capture, the merchant asks for it,
and the PR that owns manual capture owns the transition; a provider does not get to capture on its
own say-so.

**A monotonic provider-event clock.** `payment_attempts.last_provider_event_at`. A callback whose
`occurredAt` is not **strictly after** the stored value is refused.

The second is necessary because **the machine contains a cycle**:
`PROCESSING → REQUIRES_ACTION → PROCESSING` (the customer completes a 3DS challenge and the merchant
confirms again). Inside that cycle a stale `REQUIRES_ACTION` is a *legal* transition, so the state
machine has no grounds to refuse it, and the payment is dragged backwards into a challenge the
customer already finished.

**The clock is read as the maximum across all of an intent's attempts, not from the latest one.**
This is a correction made during implementation and it matters: re-confirming opens a *new* attempt,
whose own `last_provider_event_at` is null. A per-attempt read is null exactly when the cycle has
just closed, waves the stale event straight through, and passes every other test.

### 5. Ties are refused, and that is a trade rather than a fix

A callback whose `occurredAt` *equals* the stored value is refused.

Two genuinely different events sharing a timestamp — a provider emitting `AUTHORIZED` and `SUCCEEDED`
in the same second — means the second is dropped and the payment strands in `AUTHORIZED`.

**Refusing a tie trades "moved backwards" for "never moved forward". It does not eliminate a failure
mode; it chooses between two.** It is the better choice, because a stranded payment is visible in the
data and recoverable by a human, while a reversed one is neither — but it is not safety, and calling
it safety would be the kind of thing that is discovered in month three.

**The proper fix is a provider sequence number rather than a timestamp**, and this is the strongest
argument for adding one. A monotonically increasing per-attempt counter emitted by the provider
orders events exactly, has no resolution limit, and does not depend on the provider's clock. The
Provider Simulator can emit one when it exists; the field would sit beside `last_provider_event_at`
and the comparison would move to it. Until then the timestamp is what there is, and second-resolution
ties are a real, accepted hole.

### 6. Refusals answer `200` and are still recorded

`IGNORED_STALE` and `IGNORED_TERMINAL` both return `200`, and both still write the
`provider_callbacks` row.

- **`200`, never `409`**, per the retry argument above.
- **Recorded**, because a refused event that left no trace would be re-judged from scratch on every
  re-delivery, and because an `IGNORED_TERMINAL` row is the only record of a genuine divergence.

The divergence is real and named: a merchant cancels an abandoned `REQUIRES_ACTION` intent (ADR-011
requires that cancel to exist, or the order is dead), and the customer then completes the challenge.
The provider believes it collected; PayMesh has a `CANCELLED` intent. Nothing here resolves that —
resolving it is reconciliation's job (SDD §24.1) — but the row is what makes it findable, with the
outcome as the index into it. **The race is not being pretended away.**

### 7. The one non-2xx is `404`, for an intent PayMesh does not know, and nothing is stored

Two decisions, both wanted:

- **Nothing is stored**, because there is nothing to deduplicate for an event that had no effect, and
  writing rows keyed on a caller-chosen event id for intents that do not exist is unbounded write
  amplification on an endpoint reachable with one shared secret.
- **The code is `404` rather than a stored-and-ignored `200`**, because here a retry is exactly what
  should happen: the likeliest cause is a callback overtaking the transaction that created the
  intent. The bound on the amplification is therefore the provider's own retry budget.

It does mean the endpoint tells its caller whether an intent exists. The caller is the provider, which
necessarily knows.

### 8. The HMAC is the authentication, and it is fail-closed

`/internal/v1/provider-callbacks/**` is `permitAll()` on the Spring chain — a provider has no PayMesh
account — and a dedicated filter verifies `X-PayMesh-Signature: t=<unix-seconds>,v1=<hex>`, an
HMAC-SHA256 over `t + "." + body` using `paymesh.provider.callback-secret`.

- **The timestamp is inside the signed string.** A signature over the body alone replays forever; an
  unsigned timestamp is rewritten by whoever captured it. Signed together, a captured signature is
  valid for one body at one moment. ±300 seconds, both directions — a future stamp is one minted to
  outlive the window, not clock drift.
- **`MessageDigest.isEqual`.** A `String.equals` on an HMAC leaks the expected value one byte at a
  time, and that value is the ability to forge a `SUCCEEDED` callback for any payment on the platform.
- **One answer for every failure**, `401` with no detail. Which check failed tells an attacker whether
  they hold the secret.
- **`DevelopmentSecretGuard` covers it**, on the same terms as the JWT key and for a sharper reason:
  this secret is the *only* authentication on the endpoint that marks payments collected.

The route is deliberately not under `/api/`, and deliberately not in `IdempotentRoutes` — that layer
keys on merchant + endpoint + `Idempotency-Key` from a verified merchant token, and a provider has
none of the three. `provider_callbacks` is this endpoint's deduplication and the two must not be
confused.

## Consequences

- **Two identical callbacks contend on the intent's row lock before they reach the primary key.** They
  name the same intent, so the lock is what orders them in practice and the key is what decides the
  second's answer. The key still earns its place: without it the second would apply the event again
  after the first committed.
- **The deduplication key looks wrong to anyone applying the house style.** The mitigation is a
  comment in `V10` and a test that fails if the key is narrowed. It is a permanent maintenance
  hazard, and the honest answer is that it is cheaper than the alternative.
- **Second-resolution ties silently strand payments.** §5. No sweeper exists to find them; the
  `provider_callbacks` row is the only trace.
- **A refused callback is indistinguishable to the provider from an applied one at the HTTP level
  except by reading `outcome`.** That is intentional — the status code is the retry signal and the
  body is the detail — but a provider that ignores the body will believe every delivery landed.
- **`PROCESSING` still has no exit but a callback** (ADR-011 §5). This ADR makes callbacks safe; it
  does not make them arrive. A lost callback strands an order and recovery is manual until a
  `PROCESSING` timeout and provider reconciliation exist (SDD §21.4, §24.1). Neither is in scope.
- **The transition logic is reusable by design.** It lives in `RecordProviderCallbackService`, not in
  the controller, so the reconciliation job that does not exist yet can call it with the same command
  and get the same idempotent effect. A controller holding the logic would have forced that job to
  reimplement it, and two implementations of "what does this event mean" is how a payment gets applied
  twice.
- **`payment_intents` gains a second unscoped read path.** `findForProviderCallbackForUpdate` takes no
  merchant. It is named so it cannot be reached for by accident, and it is the only such method; every
  merchant-facing read still takes a `MerchantId` as its authorization.

## Deliberately out of scope

- **The Provider Simulator** (SDD §13). This PR builds the endpoint it will call. Tests and Postman
  drive it directly, which is also how a reconciliation replay will.
- **Lost-callback recovery** (SDD §21.4) and the `PROCESSING` timeout. Both need the simulator's
  reconciliation file and a job.
- **A provider sequence number.** §5 is the argument for it; nothing is built for it, because the
  field has no producer.
- **Per-provider signing secrets and rotation.** One secret, one provider. The shape would be a map
  keyed by provider name, and building it now would be a guess about a provider that does not exist.
- **Callback retry and ordering telemetry.** `received_at` minus `occurred_at` is how late a delivery
  was; nothing reads it yet.
