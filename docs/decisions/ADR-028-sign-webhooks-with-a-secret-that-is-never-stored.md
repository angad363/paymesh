# ADR-028: Sign webhooks with a secret that is never stored

- **Status:** Accepted
- **Date:** 5 August 2026
- **Related:** [ADR-016](ADR-016-dispatch-events-in-process-behind-a-brokers-contract.md) (the
  dispatcher this consumes), [ADR-017](ADR-017-simulate-providers-through-scheduled-signed-callbacks.md)
  (the outbound-delivery machine this deliberately duplicates),
  [ADR-025](ADR-025-give-up-on-an-outbox-event-rather-than-freezing-its-aggregate.md) (the retry
  budget whose shape this copies and whose numbers it does not),
  [ADR-003](ADR-003-opaque-prefixed-identifiers.md) (gains `whv_` and `whd_`),
  [ADR-022](ADR-022-authenticate-machines-with-merchant-api-credentials.md) (the hashed-secret
  pattern this case cannot use), SDD §18
- **Migrations:** V24 (endpoints, events), V25 (deliveries)
- **Amends:** `docs/phase-2-plan.md` PR 1 — three tables, not four

---

## 1. The gap: PayMesh knows, and the merchant does not

Phase 1 built the entire event backbone — an outbox, a relay, an in-process dispatcher, a
`processed_events` inbox, a retry budget with a dead letter — and every merchant-facing event
reaches an **empty handler list**. A merchant learns that their payment succeeded by polling for
it. `EventDispatcher` has carried `payment.succeeded` to nobody outside PayMesh since ADR-016.

This is the capability that makes the backbone visible from outside the process.

## 2. Decision: derive the signing secret, never store it

Every conventional webhook design stores a per-endpoint secret encrypted at rest. In this codebase
that is not a column, it is a **subsystem**: there is no `Cipher`, no `AES`, no key management and
no encrypted column type anywhere in twenty-three migrations.

`ApiCredential` (ADR-022) looks like the precedent and is not. It stores a **hash**
(`^[0-9a-f]{64}$`), which works because verifying an inbound secret never needs the plaintext back.
**A signing secret is the opposite case.** PayMesh must reproduce it to stamp every outgoing
payload, so a hash is useless and the choice is genuinely between reversible storage and something
else.

The something else is derivation:

```
secret_bytes = HMAC-SHA256(masterKey, info || 0x01)
info         = "paymesh.webhook.v1|" + endpointId + "|" + secretVersion    (US-ASCII)
secret       = "pmsec_" + Base64Url-no-padding(secret_bytes)
```

The endpoint row holds `secret_version int not null default 1` where the secret would have been.
Rotation increments it. There is no ciphertext, no decrypt path, and nothing in the database whose
leak lets an attacker sign as PayMesh.

**The blast radius argument, because it is the one that decides this.** A leaked master key
exposes every endpoint's secret. So does a leaked encryption key that unwraps every stored secret.
The two options have *identical* worst cases, and one of them requires building, reviewing and
rotating a cipher subsystem. The only capability given up is a merchant supplying their own
secret, which Stripe, GitHub and Shopify all decline to offer.

### 2.1. This is one block of HKDF-Expand, and NOT `javax.crypto.KDF`

**JDK 21 has no HKDF.** `javax.crypto.KDF` and `javax.crypto.spec.HKDFParameterSpec` are JEP 478
(preview, JDK 24) and JEP 510 (final, JDK 25). This project pins `<java.version>21</java.version>`
and runs 21.0.10. There is no BouncyCastle, Tink or commons-crypto on the classpath, and
`nimbus-jose-jwt` (arriving via `spring-security-oauth2-jose`) ships ConcatKDF but not HKDF.

The design document for this PR asserted the opposite for one draft, and that error mattered: an
implementer who believed it would have added BouncyCastle, converting "the subsystem disappears"
into "adds a crypto dependency" and reversing this decision. It is recorded here so the correction
survives the document.

What is actually built is `T(1)` of RFC 5869 §2.3 — one `Mac.getInstance("HmacSHA256")` call,
which `ProviderCallbackSignatureFilter` already imports. **Extract is skipped**, which RFC 5869
§3.3 permits when the input keying material is already a uniformly-random fixed-length key.

**That licence has a precondition, and it is enforced rather than assumed.** The master key must be
at least 32 bytes of raw entropy. A typed passphrase in that property withdraws §3.3's permission
and silently weakens the whole scheme.

**Two checks, in two classes, because they answer different questions.**
`DevelopmentSecretGuard` asks *where did this value come from* and refuses the published
development one — it says nothing about length, and an earlier draft of this paragraph wrongly
claimed it did. `WebhookSecrets.requireStrongMasterKey` asks *is this long enough for §3.3* and is
called eagerly from `WebhookConfiguration`, so a short key fails at startup rather than on the
first merchant to register. That eager call was missing when this shipped and review caught it;
`WebhookMasterKeyStartupTest` now pins both halves, including the case that passes one check and
fails the other.

### 2.2. Pinned, because a silent change breaks every merchant at once

SHA-256. No salt. `info` exactly as above, `|`-separated, version in decimal, US-ASCII. Thirty-two
bytes out. Base64 URL-safe without padding. `pmsec_` prefix.

#### The prefix is `pmsec_`, and it used to be `whsec_`

`whsec_` is Stripe's, and using it was the obvious call: it is the string an integrator already
recognises in a log or a support ticket.

**GitHub's secret scanner recognises it too.** Within minutes of the first push it opened two
"Stripe Webhook Signing Secret" alerts against the known-answer vectors below — which are HMAC
outputs of a test master key printed on the line above them, for a platform that moves no money.
The alerts were false, and that is exactly the problem: they would recur on every scanner, on every
future commit touching these lines, and on **any merchant who ever commits one of their own**, each
time labelling a PayMesh secret with another company's name.

A prefix exists to be recognised. One that gets a PayMesh secret recognised as somebody else's is
failing at its only job, so it is now `pmsec_`. The derivation is untouched — the prefix is not an
input to the HMAC, and the base64 bodies in the vectors below are byte-for-byte what they were.

Nothing sensitive was ever exposed, and the two alerts can be dismissed as false positives.

**A known-answer test vector is part of this decision, not a nicety.** The day someone "tidies" the
`info` string, every merchant's verifier starts rejecting every delivery and nothing in the suite
notices, because every test would derive and verify with the same changed formula. The vector below
is asserted by `WebhookSecretsTest` and must never be regenerated to match new behaviour — if it
fails, the behaviour is wrong.

```
masterKey      = "paymesh-test-master-key-32-bytes"  (US-ASCII, exactly 32 bytes)
endpointId     = whe_00000000-0000-4000-8000-000000000001

secretVersion 1 => pmsec_FSviFzV65R0qahrGjj1MseU2BmYQkc3rL9OriJPlsqI
secretVersion 2 => pmsec_Jfw3jylWLHXSqFjcJhBGKicygTdWQ14fmR6fg5KCDyU

endpointId     = whe_00000000-0000-4000-8000-000000000002
secretVersion 1 => pmsec_QhFfv_GCGBHpDnmVoH84FoFsMWyZEGlQoFkLY0zHNCA
```

Three vectors rather than one, because they pin three separate properties: that the formula is
what this document says, that rotation actually changes the bytes, and that one endpoint's secret
is useless against another.

### 2.3. Rotation overlaps, and the outbound header is a superset of the inbound one

A single signature cannot verify under two secrets. Without an overlap a merchant eats failures
between calling rotate and deploying their new verifier, so `previous_secret_version` stays valid
until `previous_secret_expires_at` and the dispatcher emits **two** `v1=` values during the window:

```
X-PayMesh-Signature: t=<unix seconds>,v1=<hex under current>,v1=<hex under previous>
```

The merchant accepts if **any** `v1` matches. This is Stripe's scheme and the reason it exists.

**The trap worth naming.** `ProviderCallbackSignatureFilter.Signature.parse` loops the
comma-separated elements and keeps the **last** `v1` it sees. That is correct for its job —
verifying one inbound signature — and would be wrong here. The two directions share a wire format
and **do not share a parser**. A later refactor that "unifies" them silently breaks every rotation
window.

## 3. Decision: three tables, not the four the plan reserved

`docs/phase-2-plan.md` PR 1 specifies four tables and assigns V25 to "deliveries, attempts". **This
ADR overrides that**, and the plan is amended in the same PR so the two do not disagree silently.

| Table | Why it exists |
|---|---|
| `webhook_endpoints` | Where to send, what to send, whether it still works |
| `webhook_events` | The external payload, **frozen once**, shared by every subscribed endpoint |
| `webhook_deliveries` | One row per (event, endpoint), with the attempt state on it |

`webhook_delivery_attempts` is **not built.** It would carry per-attempt status, duration and
response excerpt — real forensics, but forensics the delivery row's `attempts`,
`last_status_code`, `last_response_excerpt` and `next_attempt_at` already answer for a merchant
debugging a failure. It is a log wearing a table's clothes, and it can be added later without
touching either table that carries an invariant.

`webhook_events` is the one that does carry an invariant, and it is not deduplication of storage.

### 3.1. Replay forces byte-identity, and byte-identity forces `text`

`POST /webhook-deliveries/{id}/replay` must resend **the same bytes**. Not equivalent JSON — the
same bytes, because the merchant recomputes an HMAC over the body and a single character of drift
fails it. One frozen payload shared by N endpoints is the only shape that guarantees that.

So `payload` is **`text`, not `jsonb`.** JSONB is a normalizing type: whitespace stripped,
duplicate keys dropped, key order not preserved. This repo's jsonb columns round-trip through
`Map<String,Object>` and Jackson (`OutboxEventJpaEntity`), so the bytes on the wire would be
whatever Jackson re-emits from a rehydrated map, not what was written. Storing the serialized body
as `text` behind an immutability trigger is what makes the invariant true rather than aspirational.

The same reasoning reaches the wire: the request is sent as
`Content-Type: application/json; charset=utf-8` with the body written as raw UTF-8 bytes, never
handed to a converter that may re-serialize it.

### 3.2. Replay is why this PR is not smaller, and that is a choice

Cutting replay would collapse the design to two tables and delete an endpoint, a table, a trigger
and the hardest test. It is kept because SDD §18.3 lists it and because a merchant who missed a
delivery has no other recovery. Recorded so the cost is visible.

## 4. Decision: duplicate the simulator's delivery machine on purpose

`simulator/domain/OutboundCallback` already models a queued outbound HTTP delivery with status,
attempts, `deliverAfter` and `lastResponseStatus`. `CallbackSender`,
`DispatchProviderCallbacksService` and `SimulatorCallbackDispatcher` already do the sending, the
backoff and the scheduling. Webhook needs the same machine pointed the other way, and **builds its
own.**

Extracting a shared abstraction would couple PayMesh's merchant-facing delivery to **its own test
double.** The simulator is a fake acquirer — conceptually outside PayMesh, reachable at `/sim/v1`
rather than `/api/v1`, and slated for extraction as a separate service (ADR-001). A shared
`OutboundDeliveryEngine` would have to be extracted with it or left behind by it, and neither
answer is good.

What *is* copied verbatim is the **claim idiom**, because it is subtle and already correct: an
unlocked `findDue(now, Pageable)` builds a candidate list, then each candidate is claimed
individually with `@Lock(PESSIMISTIC_WRITE)` plus
`@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))` — Hibernate's
undocumented encoding for `SKIP LOCKED` — with the status re-checked *inside* the lock, one
transaction per row. Not a batch `SELECT ... FOR UPDATE SKIP LOCKED`, which is a different
concurrency shape.

## 5. Decision: the merchant's endpoint cannot touch the payment

**The invariant.** A merchant endpoint being down must never affect a payment.

Mechanically that means **no outbound HTTP inside the dispatcher's transaction, ever.** The event
handler only writes rows: one `webhook_events` row and one `PENDING` delivery per subscribed
active endpoint. A scheduled dispatcher sends them later, one transaction per row.

It also means the fan-out is bounded. A merchant is capped at **20 endpoints**, so the handler's
work inside the money path has a ceiling. That cap is an application check and is the one place
this design does not follow the house "prefer a database constraint" rule — a per-tenant row-count
ceiling is not a `CHECK`, and a counting trigger on every insert costs more than it buys. Stated so
the exception is visible rather than looking like an oversight.

## 6. The four numbers, and why these

Nothing below is derived from production data, because there is none. Each is a defensible first
choice with the reasoning attached so a later change is an argument rather than a coin flip.

**Retry schedule: 1m, 5m, 30m, 2h, 6h — six attempts, then `FAILED`.** Five waits carry six
attempts, and the arithmetic is spelled out because getting it wrong is silent: the total retry
horizon is 8h36m. **It shipped as 2h36m.** `MAX_ATTEMPTS` was `BACKOFF.size()`, so the delivery
was declared dead on the fifth attempt and the six-hour wait was never reached — every document
quoting this figure was right and the code was not. `WebhookDeliveryTest.spansTheRetryHorizonAdr028Claims`
now asserts the total as one figure so the two cannot drift apart again. That is deliberately long enough to survive a merchant's overnight
deploy or an outage in a timezone where nobody is awake, and deliberately short of a day so a dead
endpoint does not hold rows indefinitely. It is a different scale from ADR-025's outbox budget on
purpose: an outbox event failing repeatedly is a bug, while a merchant returning 503 for six hours
is ordinary operation.

**Endpoint disabled after 20 consecutive dead deliveries.** A *dead* delivery is one that exhausted
its own six attempts, so twenty of them is a hundred and twenty failed HTTP requests before PayMesh stops
trying. Enough that intermittent trouble never disables a working integration; few enough that a
permanently-dead endpoint stops costing. Any success resets the counter to zero. **One dead
delivery increments it by exactly one** — the two counters are separate and the interaction is
stated because six-per-delivery versus one-per-delivery is a factor-of-six difference in when an
endpoint disables.

**Rotation overlap: 24 hours.** One full day covers a normal deploy cycle for a merchant who
rotates and then ships their verifier change. Short enough that an old secret is not live for a
week.

**Response body: at most 4 KiB read from the socket, first 512 characters stored.** A cap on the
*read*, not a truncate after reading, or a merchant returning 10 MB is PayMesh's memory problem.
Nothing downstream needs more than an excerpt to debug.

## 7. SSRF is the largest exposure here, and it is not the secret

A merchant supplies a URL and PayMesh's own server POSTs to it. `https://` alone is not validation:
`https://127.0.0.1:8080/api/v1/...` reaches inside PayMesh's trust boundary, and
`https://169.254.169.254/` is a cloud metadata endpoint.

- Userinfo (`https://user:pass@internal/`) is rejected at registration; a `CHECK` cannot see it.
- The destination is resolved and loopback, link-local, RFC1918 and IPv6 ULA are rejected **at send
  time**, not only at registration.
- `HttpClient.Redirect.NEVER`. A 302 to a metadata address defeats every check above.
- Explicit connect and read timeouts, and the 4 KiB read cap.
- A `dev`-profile allowlist, so localhost testing still works.

**What this does not close.** Resolve-then-connect still has a window between the resolution and
the socket unless the connection is pinned to the validated address. That is the same class of gap
that makes a registration-time check weak, only narrower. Closing it properly means connecting to
the validated IP with the original `Host` preserved, which is real work and is **not** in this PR.
Recorded as a known limitation rather than described as solved.

## 8. Consequences

- **`whv_` and `whd_` join ADR-003.** `evt_` could not be reused for `webhook_event_id`: it is
  already the outbox's (`shared/outbox/domain/EventId`), and ADR-003's whole point is that a prefix
  names the type so a mis-routed id is rejected rather than misinterpreted. The outbox's `evt_`
  value lives in `webhook_events.source_event_id`, which is also the natural key that makes the
  handler idempotent.
- **Four handler beans, not one.** `EventHandler.eventType()` returns a single string and
  `EventDispatcher` indexes on it, so PR 1 registers one bean per subscribed type:
  `payment.succeeded`, `payment.failed`, `refund.succeeded`, `order.paid`. They may share a
  `consumerName` — not because the constructor check is per event type, but because
  `pk_processed_events (consumer_name, event_id)` cannot collide when an `event_id` belongs to
  exactly one type.
- **`order.partially_paid` is not delivered.** `ApplyPaymentSucceededService` emits `order.paid` or
  `order.partially_paid` from one ternary. A merchant subscribed to order events hears nothing on a
  partial capture. A known gap, deferred to keep this PR at four handlers.
- **Nothing consumes `webhook.delivery.failed` yet.** It is raised through the outbox when an
  endpoint disables; Notification (PR 5) is its intended reader. An unhandled event is fine by
  ADR-016.
- **The dispatcher is off under `dev`**, like every other timer.
  `paymesh.webhook.dispatch.enabled` follows the house shape (capability, then job). A live walk
  needs it and `PAYMESH_EVENTS_OUTBOX_RELAY_ENABLED=true` together, or the queue never drains.
- **Create and rotate do NOT use the `IdempotencyFilter`, and that is a consequence of §2.**
  `IdempotencyRecordJpaEntity` persists the whole response body so a retry can replay it, so
  registering a route that returns a secret would write that secret to
  `idempotency_records.response_body` in cleartext — in the same database whose dump §2 uses to
  reject plaintext storage. Only `replay` registers.

  **Rotate is idempotent; create is not, and an earlier draft of this bullet claimed both were.**
  It said create was "naturally idempotent on `UNIQUE (merchant_id, url)`" — that a retry would
  find the existing endpoint and re-derive its secret. It does not: a second create at the same URL
  answers `409` with no secret, which `WebhookIntegrationTest.refusesASecondEndpointAtOneUrl` pins.
  The claim was wrong and the behaviour is right: handing the secret back to whoever POSTs a URL
  that already exists would turn create into "reveal this endpoint's secret", which is the thing
  showing it once exists to prevent.

  The honest form of the argument is narrower and still sufficient: **a lost create response is
  recovered by rotating, not by retrying.** Rotate takes a `from_version`, so asking twice
  re-derives the same secret rather than bumping again (§2) — its response can be lost and asked
  for again safely. The merchant is therefore never stranded without a secret, which is the only
  thing the filter would have bought on these two routes, and it is bought without writing a secret
  to `idempotency_records`.
