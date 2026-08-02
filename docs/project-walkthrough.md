# PayMesh — What Exists Today, In Plain English

_A walkthrough of everything built so far, why each piece exists, how to exercise it in
Postman, and what the Software Design Document still calls for that has not been written._

_Written 2 August 2026, from the code — not from the older summaries. Where `README.md`
and `docs/project-status.md` disagree with this file, this file matched the source when it
was written._

---

## 1. The one-paragraph version

PayMesh is a **Payment-as-a-Service backend**: the software a business (a "merchant")
would integrate with to take money from their customers, in the same shape Stripe or
Razorpay work. It is educational — **no real money moves, no card networks are contacted,
no compliance is claimed.**

Right now it can do this, end to end: a business signs up, creates a login, records a
customer, raises an order (a bill), opens a payment intent against that bill, attaches a
payment method, confirms it, receives the "here's what happened" callback from a payment
provider, and either collects the money or releases it. Along the way it refuses to
double-charge, refuses to let one business see another's data, and writes an audit trail.

**Five of the eight Phase-1 capabilities are built.** The remaining three — Provider
Simulator, Ledger, Refund — are not started, and the biggest structural gap is that the
event system has a mailbox but no postman (details in §7).

---

## 2. The vocabulary, before anything else

These five words appear everywhere, and most confusion about the project is really
confusion about these.

| Word | What it actually means here |
|---|---|
| **Merchant** | A business using PayMesh. The tenant. Everything in the database belongs to exactly one merchant. |
| **Customer** | A person who buys from a merchant. Belongs to that merchant only — merchant A and merchant B can both have "alice@example.com" and they are two unrelated rows. |
| **Order** | **The bill.** "Alice owes us ₹40.00." It is a statement of what is owed, and it does not move money. |
| **Payment Intent** | **The attempt to collect that bill.** A separate object from the order on purpose: an order is what is owed, an intent is one try at collecting it, and a try can fail without destroying the bill. |
| **Provider** | The outside company that actually touches the card network. PayMesh has no real one, so the **Provider Simulator** stands in — a fake provider that behaves like one on purpose: it succeeds, declines, asks for a 3DS challenge, goes silent, or sends the same callback twice. It talks to PayMesh only over HTTP, exactly as a real provider would, so nothing in PayMesh knows it is fake. |

Two conventions that will otherwise look like bugs:

- **Money is always an integer in the smallest unit.** ₹40.00 is `4000`, not `40.0`. There
  are no decimals anywhere. Floating-point maths on money loses fractions of a paisa, and
  a payment system that loses fractions is a payment system that cannot be audited.
- **IDs are opaque prefixed strings** — `mrc_…`, `usr_…`, `cus_…`, `ord_…`, `pi_…`. Never
  `1`, `2`, `3`. A sequential ID tells a competitor how many customers you have and invites
  guessing at other people's rows.

---

## 3. The services that are built

Each subsection: what it is in human terms → what it can do → the non-obvious thing it
protects.

---

### 3.1 Merchant — the sign-up desk

**In plain terms:** the front door. A business registers itself and gets a `mrc_` ID.
This must be doable before anyone has an account, because you cannot log in to a system
you haven't joined yet.

| What | Route | Login needed? |
|---|---|---|
| Register a business | `POST /api/v1/merchants` | **No** — public |
| Look up a business | `GET /api/v1/merchants/{id}` | Yes |

**The non-obvious bit:** the *database* enforces "one account per email address", not the
Java code. The code checks first, but only so the error message is friendly. If two
sign-ups for the same email arrive at the exact same millisecond, the code's check passes
for both — and PostgreSQL's unique constraint is what stops the second one. That pattern
(app check for readability, database for truth) repeats throughout the project.

**What's missing:** this endpoint is public and has **no rate limit**. Anyone can hammer
it and create rows forever. It's a known, written-down hole.

---

### 3.2 Identity & Access — the login system

**In plain terms:** usernames, passwords, and the tokens that prove who you are on every
later request.

| What | Route |
|---|---|
| Create a user account | `POST /api/v1/auth/register` |
| Log in | `POST /api/v1/auth/login` |
| Get a fresh token without re-typing the password | `POST /api/v1/auth/token/refresh` |
| Log out | `POST /api/v1/auth/logout` |

Logging in gives you **two** tokens, and the difference matters:

- **Access token** — a JWT, lives **15 minutes**, sent as `Authorization: Bearer …` on
  every request. Short-lived because it *cannot be cancelled*. Nothing checks a blocklist,
  so its 15-minute lifetime literally *is* the "how long until a stolen token is useless"
  window.
- **Refresh token** — a random 256-bit string, lives **30 days**, only ever sent to the two
  refresh/logout endpoints. Long-lived because it *can* be cancelled — it's a database row,
  and deleting the row kills it instantly.

**Three non-obvious protections:**

1. **Login is not an oracle.** "Wrong password" and "no such user" return byte-for-byte
   identical responses. Even the *timing* is the same — an unknown email still runs a full
   password-hash check against a dummy hash, so an attacker can't detect a real email by
   noticing the server answered faster. Account status (suspended, etc.) is checked *only
   after* the password verifies, which is why a suspended user gets `403` and not `401`.
2. **Refresh tokens rotate, and reuse burns the family.** Using a refresh token gives you a
   new one and kills the old. If someone later replays the *old* one, PayMesh concludes the
   token was stolen and revokes **the entire chain**, logging both the thief and the real
   user out. Better a forced re-login than a silent intruder.
3. **The app refuses to start on the published signing key.** The development key is
   committed to git, so it's public. A guard checks at boot: if that exact string is in use
   and the `dev` profile isn't the *only* active one, the application dies at startup rather
   than signing real tokens with a key anyone can read on GitHub.

---

### 3.3 Customer — the merchant's address book

**In plain terms:** the merchant records who their buyers are, so an order can be linked to
a person.

| What | Route |
|---|---|
| Create a customer | `POST /api/v1/customers` |
| Fetch a customer | `GET /api/v1/customers/{id}` |

**The non-obvious bit:** the request body has **no `merchantId` field**, on purpose. The
merchant is taken from your login token. If callers could name the merchant they were
writing under, cross-tenant writes would be one typo away. This is true of *every*
authenticated write in the project.

**What's missing:** personal data (email, name, phone) is **stored in plaintext**. The
database is built in the *shape* encryption would need — display columns that are never
searched, separate hash columns that carry the indexes — but the encryption itself is
deferred, because encryption without key management is decoration. This is written down as
a decision, not an oversight.

---

### 3.4 Order — the bill

**In plain terms:** "this customer owes us this amount." Creating one moves no money.

| What | Route | Needs `Idempotency-Key`? |
|---|---|---|
| Create an order | `POST /api/v1/orders` | **Yes** |
| Fetch one | `GET /api/v1/orders/{id}` | — |
| List them | `GET /api/v1/orders` | — |
| Cancel one | `POST /api/v1/orders/{id}/cancel` | **Yes** |

**Statuses:** `PENDING` → `CANCELLED` (merchant asks) or `PENDING` → `EXPIRED` (the
deadline passed and a background job noticed). `PAID` and `PARTIALLY_PAID` exist in the
code but **nothing can reach them today** — see §7, the missing postman.

**Four non-obvious protections:**

1. **Two separate anti-duplicate rules, and neither replaces the other.** `Idempotency-Key`
   catches "my network timed out, I'm retrying the identical request" — it replays the
   original response. `merchantOrderReference` uniqueness catches "the user double-clicked
   Pay and a *new* request went out with a *fresh* key." One is about transport retries, the
   other about human double-submits.
2. **The customer link is enforced across both columns.** The foreign key is on
   `(merchant_id, customer_id)` together, not `customer_id` alone. With a single-column key,
   merchant A could have created an order naming merchant B's customer and only an
   application check would have stood in the way. Now PostgreSQL itself refuses it.
3. **Paging never skips a row.** Listing orders sorts by creation time *and* order ID. With
   time alone, three orders created in the same instant at `limit=2` would silently drop the
   third — every page still looking perfectly well-formed. The ID tiebreak is what prevents
   it.
4. **Order never learns Payment exists.** Order code has no idea there is a payment module.
   Payment reads Order through a small interface Payment itself defines. A test enforces
   this in both directions.

---

### 3.5 Payment — the collection attempt

The largest and most careful module. **Feature-complete** as of the most recent work.

| What | Route | Needs `Idempotency-Key`? |
|---|---|---|
| Open a collection attempt | `POST /api/v1/payment-intents` | **Yes** |
| Fetch one | `GET /api/v1/payment-intents/{id}` | — |
| List them | `GET /api/v1/payment-intents` | — |
| Say *how* they'll pay | `POST /…/{id}/payment-method` | **Yes** |
| Start the collection | `POST /…/{id}/confirm` | **Yes** |
| Take an authorized amount | `POST /…/{id}/capture` | **Yes** |
| Abandon / release | `POST /…/{id}/cancel` | **Yes** |

#### The lifecycle, told as a story

```
REQUIRES_PAYMENT_METHOD    "We want ₹40 from Alice. How will she pay?"
        │  attach
        ▼
REQUIRES_CONFIRMATION      "Card. Ready when you are."
        │  confirm  → 202 Accepted
        ▼
PROCESSING                 "Sent to the provider. We genuinely don't know yet."
        │
        ├──► REQUIRES_ACTION   "Bank wants a 3DS/OTP step from Alice."
        │        └─ re-confirm after she does it → back to PROCESSING
        ├──► AUTHORIZED        "Funds held, not taken." (manual capture only)
        │        ├─ capture → SUCCEEDED
        │        └─ cancel  → CANCELLED (release the hold)
        ├──► SUCCEEDED         "Money collected."
        └──► FAILED            "Declined."
```

**Two states are unreachable and that is correct:** `PARTIALLY_REFUNDED` and `REFUNDED`
belong to the Refund capability, which isn't built.

#### Why confirm returns `202 Accepted` and capture returns `200 OK`

This looks inconsistent and is deliberate. `202` means *"we accepted your request; the
answer isn't known yet."* After confirm, the intent is `PROCESSING` and the real outcome
arrives later, from the provider. Returning `200` would tell the caller the work is
finished, which is the one thing that is definitely untrue.

Capture, in this design, *is* finished by the time it returns — so `200` is the honest
answer. When a real provider makes capture asynchronous, that code changes with it.

#### Two payment ideas worth understanding properly

**Authorize vs. capture.** Like a hotel putting a hold on your card at check-in and
charging you at check-out. `captureMethod: "AUTOMATIC"` (the default) collects immediately.
`captureMethod: "MANUAL"` stops at `AUTHORIZED` — funds held, nothing taken — and waits for
you to call `/capture`. Capturing *less* than the held amount is allowed (you shipped two of
three items); capturing more is refused.

**Idempotency.** Every write that touches money takes an `Idempotency-Key` header — any
unique string you make up per logical operation. Rules:

| You send | You get |
|---|---|
| Same key, same body, second time | The **original response, replayed**, plus header `Idempotency-Replayed: true` |
| Same key, **different** body | `409` — you reused a key for a different operation |
| A registered route with **no** key | `400` |
| The first request is still running | `409 REQUEST_IN_PROGRESS` |
| The first request crashed with a `5xx` | Record deleted, so a retry is a **real** retry |

Seven routes are registered: both Order writes and all five Payment writes.

#### Six non-obvious protections in Payment

1. **One live intent per order, enforced by the database.** A partial unique index means an
   order can have at most one payment intent that isn't `FAILED` or `CANCELLED`. Two
   simultaneous "create intent" requests for one order produce exactly **one** intent — the
   database picks the winner. This is what makes double-collection structurally impossible
   rather than merely discouraged.
2. **The intent's amount must equal the order's exactly.** Combined with rule 1, overpaying
   an order isn't just blocked, it's inexpressible. (The direct consequence: split payments
   are impossible in this design. That's accepted, not overlooked.)
3. **Every stuck state has an escape route, and three background jobs provide them:**
   - **Abandoned checkout sweep** (30 min) — a customer opened checkout and closed the tab.
     Without this, that closed tab holds the order's only intent slot forever, and the
     merchant can never bill again under the same reference.
   - **Processing timeout** (1 hour) — the provider's callback never arrived. After an hour
     the intent is marked `FAILED`, releasing the slot. One hour is deliberately generous:
     marking it failed asserts "the payment didn't happen" with *no evidence*, and if that
     belief is wrong the merchant can collect twice.
   - **Order expiry sweep** (every 5 min) — an order past its `expiresAt` becomes `EXPIRED`,
     but **never** if a live payment intent is attached to it. Expiring an order someone is
     mid-way through paying is worse than a late status.
4. **A provider callback can never move a payment backwards.** Three independent mechanisms,
   none of which covers the others: a primary key on `(provider, eventId)` kills exact
   duplicates; a monotonic timestamp check drops events older than the last one applied; a
   row lock serializes two *different* callbacks arriving together.
5. **A provider reports an outcome; it does not set a status.** The callback body has no
   status field and no merchant ID. PayMesh derives the merchant from the intent named, and
   the *aggregate* decides whether the reported outcome is even a legal move from where the
   intent actually is. A provider claiming an amount the intent doesn't authorize is
   **recorded and refused**, never applied.
6. **The callback endpoint answers `200` even to its refusals.** Duplicate, stale, and
   too-late callbacks all return `200` with an outcome field saying which. Providers retry
   on any non-`2xx`, so answering a finished payment with `409` would create an infinite
   retry loop — a self-inflicted outage that looks like the provider's fault. The one
   exception is a callback naming an unknown intent: `404`, because the likeliest cause is
   the callback overtaking the transaction that created the intent, and a retry is exactly
   what should happen.

---

### 3.6 The provider callback endpoint — how the outside world reports in

`POST /internal/v1/provider-callbacks/{provider}`

**Not** under `/api/`, and that's a boundary, not a naming choice. This is the route that
marks a payment `SUCCEEDED`. It takes **no login token** — a provider has no PayMesh
account — and it must never be reachable with a merchant's token either, or a merchant
could mark their own payment collected.

**What stands there instead: an HMAC signature.** Same scheme Stripe uses:

```
X-PayMesh-Signature: t=<unix-seconds>,v1=<hex HMAC-SHA256 of (t + "." + rawBody)>
```

The timestamp is *inside* the signed string — that's the whole reason it's in the header. A
signature over the body alone can be replayed forever; a timestamp that isn't signed can
simply be rewritten by whoever captured it. Signing `t + "." + body` makes a captured
signature valid for exactly one body at exactly one moment, ±5 minutes for clock drift.

Every failure returns the same `401` with no hint which check failed — telling an attacker
"bad signature" vs "bad timestamp" tells them whether they have the secret.

**Known weakness:** there is **one global secret** for all providers. Whoever holds it can
name any merchant's intent. Deferred until per-provider credentials exist.

---

### 3.7 The Provider Simulator — the thing that finally calls that endpoint

`POST /sim/v1/payments` and four siblings.

For most of this project's life the endpoint above had no caller. Confirming a payment left it in
`PROCESSING` forever unless a human signed a callback by hand, which meant the states that matter
most were reachable only by someone with an HMAC key and a terminal. The simulator is the fake
provider that calls it.

**The design rule that shapes everything else: it does not know PayMesh exists.** It writes no
PayMesh table, imports no PayMesh code, and holds no shared type — its only influence is an HTTP POST
of a signed body, exactly as a real provider's would be. Delete the module and every other test still
passes. A test enforces this in both directions with no exceptions at all, which is stricter than any
other module boundary in the codebase.

**You drive it with a token, and the token decides what happens:**

| Token | What PayMesh sees |
|---|---|
| `tok_sim_success` | Payment succeeds. With `MANUAL` capture it stops at `AUTHORIZED` and waits to be captured |
| `tok_sim_decline` | Payment fails, `do_not_honour` |
| `tok_sim_3ds` | A 3DS challenge — `REQUIRES_ACTION` with a URL |
| `tok_sim_timeout` | **Nothing at all.** No callback is ever sent, and the intent strands in `PROCESSING` — the lost-callback case the 1-hour timeout exists for |
| `tok_sim_duplicate` | The same callback twice: applied once, second answered `DUPLICATE` |
| `tok_sim_stale` | Two callbacks arriving out of order: the late one refused as stale |

**Why the callback goes through a scheduler instead of being sent inline.** An inline POST from
inside the create handler would be less code and would make the module pointless: *every* failure
above is a property of **when and how often** a callback arrives, and none of them can be expressed
by code that sends one immediately and returns. Late, lost, doubled and out-of-order are the whole
product here.

**It has its own key**, `X-PayMesh-Simulator-Key`, and not the callback secret — because one value
doing both jobs means one leak does both. A merchant's login token is refused here for the same
reason it is refused on the callback route: anyone who can drive the provider can make it collect.

**The dispatcher is switched off in local development.** It is a timer, and the test suite runs under
the same profile, so it would mutate rows out from under assertions. To watch it work by hand, start
the app with `PAYMESH_SIMULATOR_DISPATCH_ENABLED=true`.

---

### 3.8 The cross-cutting platform

Not user-visible, but three pieces do most of the safety work.

**Idempotency layer** — a filter that runs *after* login is verified (it needs to know
which merchant you are before it can scope a key). It inserts and **commits** its record
before your request even reaches the controller — and that commit *is* the concurrency
control. The database's primary key picks the winner. Four simultaneous retries of one key
run the handler exactly once.

**Transactional outbox** — the "announce that something happened" mechanism. Every state
change writes both the changed row *and* a description of the event in **one transaction**.
So an event can never survive a rolled-back change, and a committed change can never lose
its event. **See §7 — nothing reads this table yet.**

**Security & tenant isolation** — two separate questions answered in two separate places:
- *Who is calling?* → the Spring Security filter chain, at the edge. Bad or missing token →
  `401`.
- *Which rows may they touch?* → next to the data, in the repository queries. Every single
  query carries `merchant_id`.

The edge deliberately does **not** answer tenancy, because it cannot see which row you're
about to touch. And a cross-tenant read returns **`404`, never `403`** — a `403` would
confirm the ID exists and turn the endpoint into an enumeration tool.

---

## 4. Everything at a glance

| Route | Auth | Idempotency-Key |
|---|---|---|
| `POST /api/v1/merchants` | public | — |
| `GET /api/v1/merchants/{id}` | Bearer | — |
| `POST /api/v1/auth/register` | public | — |
| `POST /api/v1/auth/login` | public | — |
| `POST /api/v1/auth/token/refresh` | refresh token in body | — |
| `POST /api/v1/auth/logout` | refresh token in body | — |
| `POST /api/v1/customers` | Bearer | — |
| `GET /api/v1/customers/{id}` | Bearer | — |
| `POST /api/v1/orders` | Bearer | **required** |
| `GET /api/v1/orders/{id}` | Bearer | — |
| `GET /api/v1/orders` | Bearer | — |
| `POST /api/v1/orders/{id}/cancel` | Bearer | **required** |
| `POST /api/v1/payment-intents` | Bearer | **required** |
| `GET /api/v1/payment-intents/{id}` | Bearer | — |
| `GET /api/v1/payment-intents` | Bearer | — |
| `POST /api/v1/payment-intents/{id}/payment-method` | Bearer | **required** |
| `POST /api/v1/payment-intents/{id}/confirm` | Bearer | **required** |
| `POST /api/v1/payment-intents/{id}/capture` | Bearer | **required** |
| `POST /api/v1/payment-intents/{id}/cancel` | Bearer | **required** |
| `POST /internal/v1/provider-callbacks/{provider}` | HMAC signature | — |
| `GET /actuator/health`, `GET /actuator/info` | public | — |

---

## 5. Testing it in Postman

### 5.0 Start the app

```bash
cd backend
./mvnw spring-boot:run     # port 8080; activates the dev profile via pom.xml
```

If startup fails with `Property: paymesh.security.jwt.secret / Reason: must not be blank`,
**the `dev` profile isn't active.** That's the guard working, not a misconfiguration.

Sanity check: `GET http://localhost:8080/actuator/health` → `{"status":"UP"}`.

### 5.1 The fast path — run the whole collection

A Postman collection already exists at
`docs/api/postman/paymesh.postman_collection.json`, with **10 folders and ~110 requests**
covering every route plus the failure cases. It's the fastest way to see everything work.

```bash
npx newman run docs/api/postman/paymesh.postman_collection.json \
  --env-var baseUrl=http://localhost:8080 \
  --env-var providerCallbackSecret=dev-only-insecure-provider-callback-secret-change-me
```

Or import it into the Postman app and Run Collection.

Two things that will bite you:

- **The folders must run top to bottom.** Folder 2 creates the merchant, folder 3 logs in
  and captures the token, and every folder after that uses it. Running one folder in
  isolation fails on missing variables.
- **`providerCallbackSecret` is blank in the collection and you must set it**, or every
  provider-callback request returns `401`. Use the dev value above.

The provider-callback folder computes its HMAC in a pre-request script (`CryptoJS.HmacSHA256`
over `t + "." + body`), because a hard-coded signature would be valid for one body at one
second and would test nothing.

### 5.2 The slow path — the happy flow, by hand

If you'd rather understand it than watch it pass, do these eleven steps manually. Each one
gives you a value the next one needs.

---

**Step 1 — Register a business.** No auth.

```http
POST {{baseUrl}}/api/v1/merchants
Content-Type: application/json

{
  "businessName": "Test Coffee Co",
  "email": "owner@testcoffee.test",
  "country": "IN",
  "defaultCurrency": "INR"
}
```
→ `201`. **Save `id`** (an `mrc_…`) as `merchantId`.

_Try it twice with the same email → `409`. The database refused it, not the code._

---

**Step 2 — Create a user account for that business.** No auth.

```http
POST {{baseUrl}}/api/v1/auth/register
Content-Type: application/json

{
  "email": "owner@testcoffee.test",
  "password": "a-long-enough-password",
  "merchantId": "{{merchantId}}"
}
```
→ `201`. Password must be **12–72 characters** (BCrypt silently ignores anything past 72,
which would make two long passwords equivalent). Passing `merchantId` grants this user
`MERCHANT_ADMIN` at that merchant — this is what makes every later request tenant-scoped.

---

**Step 3 — Log in.**

```http
POST {{baseUrl}}/api/v1/auth/login
Content-Type: application/json

{ "email": "owner@testcoffee.test", "password": "a-long-enough-password" }
```
→ `200` with `accessToken`, `refreshToken`, `tokenType`, `expiresIn`.
**Save `accessToken`.** Every step from here sends `Authorization: Bearer {{accessToken}}`.
It expires in **15 minutes** — if you start getting `401`s, log in again.

_Worth doing: repeat with a wrong password, then with an email that doesn't exist. Compare
the two responses. They are identical._

---

**Step 4 — Create a customer.**

```http
POST {{baseUrl}}/api/v1/customers
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "email": "alice@example.test",
  "name": "Alice",
  "merchantReference": "cust-001",
  "phone": "+919999999999"
}
```
→ `201`. **Save `id`** (`cus_…`). Note there's no `merchantId` in the body — it came from
your token.

---

**Step 5 — Create an order (the bill).** First route needing an `Idempotency-Key`.

```http
POST {{baseUrl}}/api/v1/orders
Authorization: Bearer {{accessToken}}
Idempotency-Key: order-key-001
Content-Type: application/json

{
  "customerId": "{{customerId}}",
  "merchantOrderReference": "INV-1001",
  "amountMinor": 4000,
  "currency": "INR",
  "description": "Two flat whites"
}
```
→ `201`, status `PENDING`. **Save `id`** (`ord_…`). `4000` = ₹40.00.

_Three things to try right here:_
- _Send it again unchanged → `200` with the **original** body and `Idempotency-Replayed: true`._
- _Same key, change `amountMinor` → `409`._
- _Fresh key, same `merchantOrderReference` → `409`. Different rule, different reason._
- _Drop the header entirely → `400`._

---

**Step 6 — Open a payment intent.**

```http
POST {{baseUrl}}/api/v1/payment-intents
Authorization: Bearer {{accessToken}}
Idempotency-Key: intent-key-001
Content-Type: application/json

{
  "orderId": "{{orderId}}",
  "customerId": "{{customerId}}",
  "amountMinor": 4000,
  "currency": "INR",
  "captureMethod": "MANUAL"
}
```
→ `201`, status `REQUIRES_PAYMENT_METHOD`. **Save `id`** (`pi_…`).

`MANUAL` is chosen so you get to see the authorize→capture split. Use `AUTOMATIC` (or omit
it) for the simpler path.

_Try: a second intent for the same order with a fresh key → `409`. That's the one-live-intent
index. Try `amountMinor: 3999` → `422`, exact-amount rule._

---

**Step 7 — Attach a payment method.**

```http
POST {{baseUrl}}/api/v1/payment-intents/{{paymentIntentId}}/payment-method
Authorization: Bearer {{accessToken}}
Idempotency-Key: attach-key-001
Content-Type: application/json

{ "paymentMethodType": "CARD" }
```
→ `200`, status `REQUIRES_CONFIRMATION`. Valid types: `CARD`, `UPI`, `NET_BANKING`,
`WALLET`.

Note there's no card number. Nothing in PayMesh can tokenize a card yet, so requiring a
token would make this endpoint uncallable. A *type* is the smallest thing that is truthful
today.

---

**Step 8 — Confirm.**

```http
POST {{baseUrl}}/api/v1/payment-intents/{{paymentIntentId}}/confirm
Authorization: Bearer {{accessToken}}
Idempotency-Key: confirm-key-001
Content-Type: application/json

{ "returnUrl": "https://testcoffee.test/thanks", "device": "postman" }
```
→ **`202 Accepted`**, status `PROCESSING`.

**The payment now sits still.** Nothing else will move it — no timer, no polling. The only
thing that can is a provider callback, which is step 9. This is the correct behaviour, not
a hang.

_Try: `POST /cancel` right now → `409`. Cancel is refused from `PROCESSING` because the
provider may already have succeeded, and cancelling would contradict a real payment._

---

**Step 9 — Play the provider. (The interesting step.)**

You must sign this request. In Postman, add a **Pre-request Script** to the request:

```javascript
const body = JSON.stringify({
    eventId: 'sim_evt_' + pm.variables.replaceIn('{{$guid}}'),
    occurredAt: new Date().toISOString(),
    paymentIntentId: pm.collectionVariables.get('paymentIntentId'),
    providerReference: 'sim_ref_001',
    outcome: 'AUTHORIZED',
    authorizedAmountMinor: 4000
});
const t = Math.floor(Date.now() / 1000);
const v1 = CryptoJS.HmacSHA256(t + '.' + body, 'dev-only-insecure-provider-callback-secret-change-me')
    .toString(CryptoJS.enc.Hex);
pm.collectionVariables.set('callbackBody', body);
pm.collectionVariables.set('callbackSignature', 't=' + t + ',v1=' + v1);
```

Then the request itself:

```http
POST {{baseUrl}}/internal/v1/provider-callbacks/SIMULATOR
X-PayMesh-Signature: {{callbackSignature}}
Content-Type: application/json

{{callbackBody}}
```

**The body must be literally `{{callbackBody}}`** — the variable, not retyped JSON. The
signature covers the exact bytes, so typing the JSON in the body pane means signing one
string and sending another.

→ `200 {"outcome":"APPLIED"}`. Fetch the intent: it is now **`AUTHORIZED`**, and
`capturedAmountMinor` is still `0` — funds are held, not taken.

_Four things worth trying:_
- _Send the exact same request again → `200 {"outcome":"DUPLICATE"}`, and the intent doesn't move._
- _Remove the signature header → `401`._
- _Change one character of the body without re-signing → `401`._
- _Send `authorizedAmountMinor: 9999` on a fresh event → `200 {"outcome":"IGNORED_TERMINAL"}`.
  The provider does not get to change what is owed._

---

**Step 10 — Capture the money.**

```http
POST {{baseUrl}}/api/v1/payment-intents/{{paymentIntentId}}/capture
Authorization: Bearer {{accessToken}}
Idempotency-Key: capture-key-001
Content-Type: application/json

{ "amountMinor": 4000 }
```
→ **`200`**, status `SUCCEEDED`, `capturedAmountMinor: 4000`.

Omit the body entirely to capture the full authorized amount. Send `3000` for a partial
capture — still `SUCCEEDED`, just for less. Send `5000` → `422`.

---

**Step 11 — Look at the order.**

```http
GET {{baseUrl}}/api/v1/orders/{{orderId}}
Authorization: Bearer {{accessToken}}
```

→ **status is still `PENDING`.**

**This is the single most important thing to understand about the current state of the
project, and it is not a bug.** Payment deliberately never writes the orders table. The
event announcing "this payment succeeded" *was* written — to the outbox table, in the same
transaction as the payment — but nothing delivers it and nothing consumes it. See §7.

---

### 5.3 The failure paths worth exercising

The happy path is the least interesting thing here. These are what the design is actually
for.

| Try this | Expect | Why it matters |
|---|---|---|
| Any authenticated call with no `Authorization` header | `401` | Nothing is open by accident |
| Register a **second** merchant + user, log in as them, `GET` the **first** merchant's order | **`404`, not `403`** | `403` would confirm the ID exists — an enumeration oracle |
| As merchant B, `GET /api/v1/orders` | `200` with an **empty** list | Not "filtered in the UI" — the query is scoped |
| As merchant B, create an order naming merchant A's customer | `422` | Composite tenant foreign key |
| Create an order naming a customer ID that never existed | `422`, **identical body** to the line above | Two different causes, one answer, so nothing is learned |
| Create an intent against another merchant's order | `422` `ORDER_NOT_PAYABLE` | One code for three causes: no such order, not yours, not `PENDING` |
| `GET /api/v1/orders/not_an_order_id` | `400` | ID format is validated, not looked up |
| `GET /api/v1/orders?limit=5000` | `200`, silently capped at 100 | |
| `GET /api/v1/orders?limit=0` | `400` | |
| `GET /api/v1/orders?status=NOPE` | `400` | |
| `GET /api/v1/orders?limit=1`, then follow `nextCursor` | No repeats, nothing skipped | The ID tiebreak |
| Cancel an order, then cancel it again with a **fresh** key | `409` | State machine refuses it |
| Cancel it again with the **same** key | `200`, replayed | Idempotency, not the state machine |
| Cancel the **order** while its intent is live, then confirm the intent | `422` | The confirm re-checks payability inside its own transaction |
| Refresh with a token you already spent | `401` — **and the whole family is revoked** | Reuse is treated as theft |

---

## 6. What is *not* built — the gap against the SDD

The Software Design Document describes roughly **15 services across 31 sections**. Here is
the honest accounting.

### 6.1 Phase-1 capabilities not started

Two are left. The Provider Simulator used to head this list and is now built — see §3.7. Worth
saying what it did *not* close, because it would be easy to assume otherwise: it produced the
reconciliation **file** the 1-hour timeout's recovery leans on, but **not the recovery job**, which
still does not exist. A provider *sequence number* and per-provider secrets also remain open, and
ADR-017 says why each is somebody else's PR.

| Capability | SDD | What it would do | Why it's next / not next |
|---|---|---|---|
| **Ledger** | §15 | Double-entry bookkeeping. Every transaction's debits equal its credits, entries are immutable, corrections are new reversal transactions | **Deliberately last in Phase 1**, and last to be extracted into a service. It is the financial source of truth. Until it exists, a `SUCCEEDED` payment is operational state and nothing more — **no balance moves anywhere in this codebase.** |
| **Refund** | §16 | Give money back, in full or part | Needs the Ledger first — a refund without double-entry is an untraceable subtraction. This is why `PARTIALLY_REFUNDED` and `REFUNDED` are unreachable. |

### 6.2 Phase-2+ services, none started

| Service | SDD | What it would do |
|---|---|---|
| **API Gateway / Edge** | §7 | Rate limiting, API keys, request routing. **None of it exists** — including the rate limit the public merchant endpoint needs. |
| **Risk & Fraud** | §14 | Score a payment before it's attempted; block or challenge |
| **Settlement** | §17 | Pay merchants their balance on a schedule |
| **Webhook** | §18 | Notify *merchants* of events (the mirror of the provider callbacks that come in) |
| **Notification / Reporting / Audit** | §19–20 | Email/SMS, dashboards, compliance trail |
| **AI Operations** | §20 | Explain and summarize operational state. Advisory only — must **never** post a ledger entry, move money, or approve a refund. |

### 6.3 Platform gaps

| Piece | SDD | State |
|---|---|---|
| **Kafka + event delivery** | §22.2–22.4, §24 | **The single biggest gap.** The `outbox_events` table exists and is written correctly in-transaction, but there is **no Kafka, no relay, no consumer.** Every event row sits unpublished forever. |
| **Redis** | §23.3 | Not built, deliberately. PostgreSQL is the durable authority for idempotency; Redis was only ever the accelerator. |
| **Encryption at rest / key management** | §25 | Not built. Customer PII is plaintext. |
| **Observability** | §26 | `/actuator/health` and `/actuator/info`. No OpenTelemetry, no metrics, no tracing, no correlation IDs. |
| **Deployment / IaC** | §27 | Nothing. Docker, Kubernetes, Helm, Terraform — all planned, none written. `infrastructure/` and `scripts/` are empty. |
| **End-to-end workflows** | §21 | Only the create-order → collect prefix. §21.4 reconciliation is absent. |

---

## 7. The one thing to remember: the mailbox with no postman

If you take away a single fact about the current state, take this.

Every state change writes **two** rows in one transaction: the change itself, and a
description of what happened, into `outbox_events`. That's the "transactional outbox"
pattern, and it's built correctly — an event can never survive a rolled-back change, and a
committed change can never lose its event.

**But nothing ever reads that table.** There is no Kafka, no relay process, no consumer.

The visible consequence: **an order whose payment succeeded still reads `PENDING`.** The
`payment.succeeded` event was written. The Order module *would* consume it and set the
status to `PAID`. That consumer does not exist yet.

The SDD names this exact state as non-corrupting — nothing is lost, everything is
recoverable the moment a relay is added — which is precisely why it was built this way. But
it does mean the API currently shows a paid order as unpaid, and anyone reading the API
without this context will file it as a bug.

---

## 8. Verifying the whole thing

```bash
cd backend
./mvnw test                     # full suite; needs Docker, touches no local database
./mvnw spring-boot:run          # port 8080
```

The documented count is **727 tests, 0 failures**, across 12 Flyway migrations and 15 ADRs.
_(Those figures come from `docs/project-status.md` and `README.md`; I did not run the suite
while writing this file.)_

Integration tests run against a throwaway PostgreSQL container, so Flyway migrates an empty
database on every run and the migrations are re-proved rather than assumed.

**One testing practice here is genuinely unusual and worth knowing about:** where a test
protects an invariant, the invariant is verified by *deliberately breaking the
implementation and confirming the test goes red.* A green assertion that has never failed
is not evidence — it's a line of code that compiles. Several of these breaks are recorded,
including the ones where the sabotage *didn't* turn the test red, so nobody concludes a test
covers more than it does.

---

## 9. Where the other documents disagree with this one

Worth knowing, because you'll read them next:

- **`CLAUDE.md`** says "only the merchant capability exists." That is badly out of date —
  five capabilities exist.
- **`README.md`**'s per-capability table says Payment's core is built with "three more PRs"
  remaining, and that Order has no expiry sweeper. Both are stale; its own status paragraph
  higher up correctly says Payment is feature-complete. It also says the Postman collection
  has seven folders; it has ten.
- **`docs/project-status.md`** is the most current. Its "What is built" section still
  describes Payment as create/cancel only, but its "What comes next" section correctly
  states Payment is feature-complete. Trust the latter.

The reliable source in all cases is the code.
