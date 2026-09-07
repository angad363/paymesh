# PayMesh Devlog — ChatGPT Briefs for Posts 15–36

This document feeds ChatGPT the raw material to write every LinkedIn devlog post from #15 up to the
**current state of the project**, in the **exact order you built these features** (the commit history
from right after Refunds / Post 14 through the Webhook service extraction that is `main` today). Each
brief is self-contained: hand ChatGPT one post's section plus the shared "Voice & Format" guide, and
it can produce a finished post.

Everything here is drawn from the real PayMesh codebase (ADR-021 → ADR-042). Keep the numbers exact —
they're the credibility of the whole devlog.

**Coverage:** Posts 15–20 close out Phase 1 (hardening). Posts 21–29 are Phase 2 (event-driven
capabilities). Posts 30–36 are Phase 3A–3B (the microservices extraction) — where `main` sits right
now, with the Provider Simulator and Webhook running as their own deployables.

---

## How to use this document

For each post, paste ChatGPT:

1. The **Voice & Format guide** (below) — once per conversation is enough.
2. The **one post section** you're writing.
3. This instruction: *"Write the LinkedIn devlog post from this brief. Match the voice guide exactly.
   Don't invent technical details beyond what's in the brief. End with the reflection and the
   hashtags."*

Then paste the **Image Prompt** into your image generator for the hook image.

---

## Voice & Format guide (reverse-engineered from Post 14)

Study Post 14 ("The Refund Race Condition"). The pattern that works:

- **First person, building-in-public.** "After building the Ledger, I moved on to Refunds." You're a
  developer narrating a real problem you hit, not a company writing marketing copy.
- **Very short lines. Lots of white space.** Most "paragraphs" are one sentence. This is a LinkedIn
  feed — it has to be skimmable on a phone.
- **Structure is always the same five beats:**
  1. *The rule seemed simple* — state the naive version of the feature.
  2. *Until…* — the twist, the thing that breaks the naive version.
  3. *A concrete worked example with real numbers* (Post 14 used ₹1,000 / ₹700 + ₹700 = ₹1,400).
  4. *The fix*, and crucially *why that fix and not the obvious one*.
  5. *The reflection* — one general lesson about building payment systems.
- **Bold the key phrases** using the unicode-bold style (𝗹𝗶𝗸𝗲 𝘁𝗵𝗶𝘀). Bold the rule, the failure, and
  the punchline of the fix — 3 to 5 phrases per post, no more.
- **Use ₹ for money examples.** Stay consistent with Post 14.
- **"Why both?" / "Why not X?" beats.** Post 14's strongest section was explaining why it kept *both*
  the row lock and the DB trigger. Every one of these features has a "why the obvious approach was
  wrong" — that's the intellectual hook. Lead into it with a short question.
- **Close with a one-line universal lesson**, then the hashtags.
- **Hashtags:** reuse the Post 14 set, swapping the topic-specific one. Base set:
  `#buildinpublic #backend #java #springboot #postgresql #softwareengineering` + one or two specific
  to the post (e.g. `#security`, `#eventdriven`, `#distributedsystems`, `#apidesign`).
- **Length:** ~250–450 words. Post 14 is the ceiling; shorter is fine.
- **No emojis in the body** except the ✓/✗ style checks Post 14 used sparingly.

The through-line of the whole devlog: **correctness isn't whether one request behaves — it's whether
the invariant still holds under concurrency, retries, partial failure, and time.** Every post should
land somewhere near that theme without repeating the exact sentence.

---

## The full map (Posts 15–36)

Posts 8–14 built the money path: Merchant → Identity → Customer → Order → Payment → Ledger → Refunds.
Everything below is what came after, in build order.

**Season 1 finale — Phase 1 hardening (Posts 15–20).** You can frame this openly: *after building the
happy path, I did an audit — and found the platform was full of states it could describe but could
never reach.*

| Post | Feature | The hook |
|---|---|---|
| 15 | Lifecycle states made reachable + the KYC trap | "I had four merchant states. Only one could ever happen." |
| 16 | Machine authentication (API keys) | "Servers were logging in with a human's password." |
| 17 | The ADR that lied (finishing the lifecycle) | "My own decision record claimed a feature that didn't exist." |
| 18 | Disabling people at two scopes | "The question 'who can disable a user' was the wrong question." |
| 19 | Dead-letter: giving up on an event | "One poisoned event froze an account forever. Silently." |
| 20 | Reconciliation: trust, but verify | "I marked a real payment as failed. The customer's money had already moved." |

**Season 2 — Phase 2, event-driven capabilities (Posts 21–29).** The platform starts *talking* — to
merchants, to itself, to the outside.

| Post | Feature | The hook |
|---|---|---|
| 21 | Platform admin, grantable at last | "No one could approve a merchant — because no one could become an admin." |
| 22 | Webhooks with a secret that's never stored | "Every webhook platform stores a signing secret. I stored nothing." |
| 23 | Constrain IDs in the database | "One malformed row could silently disable five background jobs." |
| 24 | Risk decides, Payment acts | "The fraud check must never be the thing that fails a payment." |
| 25 | The ledger makes its own money settleable | "When can a merchant actually be paid? Ask the ledger, not a flag." |
| 26 | Settlement — money leaves the platform | "A '200 OK' from the bank doesn't mean the money moved." |
| 27 | Notifications: record on event, send on timer | "Telling a merchant must never be able to undo the payment." |
| 28 | Reporting that admits when it's stale | "A dashboard that lies about being up-to-date is worse than a slow one." |
| 29 | An audit log that fails the action if it can't record | "For a security log, failing to write is a reason not to act." |

**Season 3 — Phase 3, the extraction (Posts 30–36).** The monolith becomes many services. This is
where `main` is now.

| Post | Feature | The hook |
|---|---|---|
| 30 | Kafka backbone + a versioned envelope | "In one process the compiler is your schema. Across a wire, there's no compiler." |
| 31 | The dual-path relay | "My safety net for going distributed nearly stalled the money path." |
| 32 | Schema-per-service (still one process) | "Nine databases' worth of walls, before splitting a single service." |
| 33 | The merchant reference projection | "To stop sharing a table, every service keeps its own copy — fed by events." |
| 34 | The API gateway | "One front door — that authenticates, then deliberately doesn't trust itself." |
| 35 | Extracting the simulator (the pilot) | "The first service to leave was the one that never mattered — on purpose." |
| 36 | Extracting Webhook (the first real leaf) | "A merchant's endpoint being down can't touch a payment. Now it's true across processes." |

Optional intro line for Post 15 to frame Season 1: *"The happy path was done. So I went looking for
what I'd lied to myself about."* And a Season-3 framing line for Post 30: *"For 29 posts PayMesh was
one program. Now I'm tearing it into nine."*

---

## POST 15 — The states that could never happen

**Suggested title:** `PayMesh Devlog #15: Four States, One Reachable`

### What we built
Made every lifecycle state in the platform actually reachable and enforced. Three aggregates —
Merchant, User, Customer — each had a status enum where only one value could ever occur. This post
focuses on the **Merchant** half (the User/Customer half becomes Posts 17–18, which is honest and
sequential). We added real state-machine methods (`activate`, `suspend`, `close`), status-history
tables, a **single security filter** that refuses writes from any non-`ACTIVE` merchant, and — in the
same change — the **KYC submission + approval** path that lets a merchant reach `ACTIVE` at all.

### The problem we're solving
A payment platform's single most important operational control, after authentication, is the ability
to **stop a merchant** — a compromised or fraudulent one. PayMesh couldn't. The audit found this:

| Enum | Declared values | Value ever actually produced |
|---|---|---|
| `MerchantStatus` | PENDING_VERIFICATION, ACTIVE, SUSPENDED, CLOSED | only PENDING_VERIFICATION |
| `UserStatus` | ACTIVE, SUSPENDED, CLOSED | only ACTIVE |
| `CustomerStatus` | ACTIVE, BLOCKED | only ACTIVE |

`Merchant` had **no state-changing method at all**. Nothing anywhere even *read* `MerchantStatus`.
So: no merchant could be suspended, every merchant transacted while unverified (verification was
decorative), and a departed employee's account couldn't be disabled. The states existed in the type
system and nowhere in reality.

### The problem we faced while developing
The interesting failure. We built the status gate **first**, planning to add KYC "later."

**84 tests failed — every one returning 403 where it expected 201.**

Registration produces a `PENDING_VERIFICATION` merchant. The new gate refuses every write from a
non-`ACTIVE` merchant. And there was no path from PENDING to ACTIVE. So every newly registered
merchant was **frozen out of the platform, permanently.** Every compile passed. Every unit test on
the new aggregates passed. Only the full suite caught it. A gate whose only entry state has no exit
isn't a partial feature — it's an outage.

### The solution
- One `MerchantStatusGate`, enforced in **a single Spring Security filter**, not a check copied into
  a dozen controllers. A rule enforced in twelve places is missing from the thirteenth — and the
  thirteenth is written by someone who doesn't know the rule exists. In a filter, a new endpoint is
  covered the moment it's added.
- Ship **KYC approval in the same change** as the gate, in the same transaction, so a green
  submission can never sit next to a still-frozen merchant. The gate and its key ship together or
  not at all.
- The gate guards **writes only** — a suspended merchant reading their own orders is not a threat,
  and blocking it just makes the incident harder to clean up.

### The trade-offs we weighed
- **The exemption list is the whole design.** Some routes *must* bypass the gate or the system
  deadlocks:
  - `/kyc-submissions` — the one write an unverified merchant must make; refuse it and ACTIVE is
    unreachable (this is the bug we just lived through).
  - `/activate`, `/suspend`, `/close` — they act *on* a non-active merchant by definition; guard
    them and **suspension becomes irreversible.**
  - **Provider & refund callbacks** — HMAC-authenticated, no user. A payment already taken must
    settle even if the merchant was suspended in the meantime. The customer's money already moved;
    refusing the callback strands it. → **Suspension stops new business; it never abandons business
    already in flight.**
- We rejected the shortcut of "register merchants directly as ACTIVE." That would fix two frozen
  enums while freshly freezing the third (PENDING_VERIFICATION) — the exact defect we're here to fix.
- We store **no KYC documents at all.** PayMesh claims no compliance; a table of passport scans would
  exist to make a checklist green, not to verify anyone, and would be the single worst thing in the
  repo.
- **A migration backfill (V17) activates every pre-existing merchant** on deploy — otherwise
  shipping a security fix would freeze every existing merchant and turn it into an outage.

### Final working & architecture
`MerchantStatusGate` is declared in the shared module and implemented by the merchant module (a
consumer-owned port). One filter reads it on every authenticated write; the token now also carries
the caller's **role** (previously the resolver kept the tenant and threw the role away, so a
`MERCHANT_USER` could refund exactly like a `PLATFORM_ADMIN`). Status changes go through intent
methods and land a row in `merchant_status_history` with an actor and a required reason (enforced by
a DB CHECK). The full lifecycle is proven end-to-end by one integration test that drives:
**register → refused → submit KYC → approve → transacting → suspend → refused → reinstate →
transacting.**

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** register → KYC approved → merchant is ACTIVE → all writes flow. One uncached
  primary-key read per authenticated write (deliberately uncached: a cache would let a suspended
  merchant keep trading for the cache's lifetime — exactly the window an incident is trying to
  close).
- **Edge — suspension timing:** suspension stops the *next* write, not one already inside a running
  handler. And access tokens stay valid for their 15-minute lifetime (no revocation list yet). So
  suspension is "within ~15 minutes," not instant. Stated honestly, not hidden.
- **Edge — in-flight money:** a callback for a payment taken before suspension still settles.
- **Behaviour change (accepted):** self-serve signup is no longer self-serve — a merchant needs
  platform approval before trading. Every test fixture had to be updated to walk a merchant through
  activation, which is itself the honest signal that the behaviour really changed.

### Image direction
**Concept:** a state-machine diagram where three of four states are ghosted/unreachable.

Draw four boxes for a merchant: `PENDING_VERIFICATION` (bright, solid), `ACTIVE`, `SUSPENDED`,
`CLOSED`. The last three are dimmed/dashed with a subtle 🔒 or "unreachable" tag, and there are **no
arrows** connecting PENDING to any of them — a dead end. A big faded "403" watermark behind it hints
at the trap. Clean dark-mode developer aesthetic, thin lines, one accent color for the live state.

**Prompt to paste into the image generator:**
> A minimalist dark-mode software architecture diagram, 16:9, for a technical blog hero image. Four
> rounded rectangular state nodes labeled "PENDING_VERIFICATION", "ACTIVE", "SUSPENDED", "CLOSED".
> The "PENDING_VERIFICATION" node glows in a single teal accent color and is solid; the other three
> nodes are dimmed, dashed-outline, semi-transparent, each with a small lock icon, clearly
> unreachable. There are NO connecting arrows between the nodes — PENDING is a dead end. A large,
> faint "403" is watermarked in the dark background. Thin clean lines, generous negative space,
> monospaced labels, professional, no clutter, no photorealism.

---

## POST 16 — Servers were logging in as humans

**Suggested title:** `PayMesh Devlog #16: An API Key Is Not a Person`

### What we built
Merchant API credentials: `Authorization: ApiKey ak_<prefix>.<secret>` — the way a merchant's
**server** authenticates to PayMesh, instead of a person's password. The key is verified inside the
Spring Security chain and produces a caller that is **indistinguishable downstream from a logged-in
human**, so every rule written for humans automatically applies to machines.

### The problem we're solving
The design always said customers and orders are created with a Merchant API key — but that endpoint
never existed. So a merchant's backend integrating with PayMesh had to log in **as a human, with a
password**: the one credential a server must never hold. A password carries far more than an
integration needs — the ability to log in interactively, rotate its own session, and act anywhere
else that person holds a role. Server-to-server integration was impossible as specified, and the
only workaround (embed a human password in a server) was worse than the gap.

### The problem we faced while developing
**Filter ordering nearly killed the feature.** The security chain ends with
`.anyRequest().authenticated()`. A filter registered the normal way (a `FilterRegistrationBean`,
which is how every other filter in the codebase is registered) runs **after** the security chain — so
an ApiKey request gets refused `401` before our filter ever sees the header.

We proved it: the fix is inserting the filter with `addFilterBefore(...,
BearerTokenAuthenticationFilter.class)`. **Remove that one line and 7 of 11 integration tests go
red — all 401.** We also had to register a *second, disabled* `FilterRegistrationBean` to stop Spring
Boot auto-registering the same filter a second time in the servlet chain, in the position that
doesn't work.

### The solution
The filter mints an **in-memory, unsigned JWT** carrying the same `roles` claim a login produces —
rather than teaching the rest of the system a second kind of principal. That's not a shortcut; it's
the whole point:

**Two principal types would be two authorization paths, and they would eventually drift** — a key
granted something a token wasn't, or the merchant-status gate applied to one and not the other.
Because the shapes are identical, every rule written for humans holds for machines for free. The
tests prove it: a key belonging to a suspended merchant is refused by Post 15's status gate, and a
`MERCHANT_USER` key cannot mint another credential.

### The trade-offs we weighed
- **SHA-256, not bcrypt, for the secret hash.** This looks wrong to a security reviewer, so explain
  it: bcrypt's cost exists to make *guessing a low-entropy human password* expensive. This secret is
  **32 bytes from `SecureRandom`** — not guessable at any hash speed. bcrypt would buy nothing and be
  paid on **every single API request**, a self-inflicted denial of service. Identity's *password*
  hashing stays bcrypt, because a human chose that input. **The difference is the entropy of what's
  being hashed, not carelessness.**
- Comparison uses `MessageDigest.isEqual` (constant-time). A plain `.equals()` short-circuits on the
  first differing byte and leaks the secret to anyone who can measure response time.
- **The secret is returned exactly once**, in a response type that's *different* from the one every
  read uses — so a nullable `secret` field can't accidentally leak on a list endpoint.
- **`sub` is the credential's id, not a user's.** An API key is not a person; attributing a machine's
  writes to whoever created the key would put the wrong name in every audit row.
- **No key may be `PLATFORM_ADMIN`.** A string in a config file must not be able to suspend merchants
  or approve KYC. Refused by the aggregate and a DB CHECK.
- Unknown / malformed / revoked / wrong-secret all return **one identical answer** — distinguishing
  them confirms which key prefixes exist, and a revoked key answering differently tells an attacker
  they once had something real.

### Final working & architecture
`SecurityConfiguration` and the filter live in the shared module (it can't import a capability); the
credential store answers through an `ApiKeyAuthenticator` port implemented by the merchant module —
the same shape as Post 15's status gate. Revocation is a **timestamp, not a delete** — a deleted
credential can't answer "was this key live when that payment was taken," which is exactly the
question an incident asks.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** merchant creates a credential once, stores the secret, and every future
  server-to-server call authenticates with one indexed prefix lookup + one constant-time hash
  compare. No password anywhere.
- **Edge — hot-row write:** `last_used_at` is **throttled to one write per 10 minutes**. Writing it
  on every request put a row UPDATE (WAL write + row lock) on the auth path of every call, making a
  busy key's row the hottest in the system. Ten minutes of staleness answers "has this key been used
  lately" just as well, and it can never fail a payment.
- **Edge — no expiry:** a key is live until revoked. Rotation is a manual discipline, not something
  the platform enforces yet — stated as a known gap.
- **Edge — suspended merchant:** an ApiKey for a suspended merchant is refused by the Post 15 gate,
  no extra code needed. That's the payoff of "indistinguishable downstream."

### Image direction
**Concept:** the anatomy of the key, plus the "one filter, before the wall" idea.

Show the token dissected: `ak_` (prefix, public, labeled "lookup") · `.` · `secret` (labeled "32
random bytes, hashed once"). Below it, a pipeline of filters with an arrow slotting the ApiKey filter
**in front of** the "Bearer token" filter, ahead of a wall labeled `anyRequest().authenticated()`.

**Prompt:**
> A clean dark-mode technical diagram, 16:9. Top half: a dissected API key string
> "ak_9f2c.·······················" broken into two labeled parts — "ak_9f2c" tagged "public prefix ·
> lookup" and a masked secret tagged "32 random bytes · hashed with SHA-256". Bottom half: a
> horizontal pipeline of stacked filter blocks like a conveyor, with one highlighted teal block
> labeled "ApiKey filter" being inserted BEFORE a block labeled "Bearer token filter", both ahead of
> a solid wall labeled "anyRequest().authenticated()". Monospaced labels, single teal accent on a
> near-black background, thin lines, lots of negative space, professional, no photorealism.

---

## POST 17 — The decision record that lied

**Suggested title:** `PayMesh Devlog #17: My Own ADR Claimed a Feature That Didn't Exist`

### What we built
Finished the lifecycle work Post 15 only *claimed* to finish. Three things: (1) made
`CustomerStatus.BLOCKED` genuinely reachable and enforced; (2) gave the `payment_method_tokens`
table its **first-ever writer** (attach / list / detach a saved payment method); (3) added a timeout
sweeper for refunds stuck in `PROCESSING`.

### The problem we're solving
Post 15's decision record stated, in its consequences: *"A merchant can be stopped. A user can be
disabled. A customer can be blocked."* **Only the first was true.** `Customer.block`,
`Customer.unblock`, `User.suspend`, `User.reactivate`, `User.close` all existed as aggregate
methods **that nothing ever called** — no service, no endpoint. The states stayed exactly as
unreachable as before. It's the same defect Post 15 was written to fix, one layer up: a capability
the domain can express and the system can't produce. Worth naming plainly, because the ADR was
written *while* building the fix and the claim was believed at the time. An audit that only read the
domain model would have agreed with it.

### The problem we faced while developing
Two "this has never worked" discoveries and one nasty money bug:

1. **`payment_method_tokens` has existed since migration V3 and nothing ever inserted a row.** It was
   even tenant-foreign-key-*fixed* in V6 — a bug fixed in a table nobody could put a row in. "Attach
   a payment method" on a payment intent was attaching a payment method **type** (the literal string
   `CARD`); no card was ever actually on file for any customer.
2. **A lost refund callback held refundable head-room hostage forever.** A `PROCESSING` refund counts
   against the captured amount. So one refund that the provider never answered permanently reduced
   what the merchant could refund on that payment — a ₹4,000 refund that vanished blocked ₹4,000 of
   head-room for good, and **nothing reported it.** The merchant discovered it only when a legitimate
   refund was rejected as an over-refund. Payment had had a sweeper for exactly this shape since
   Post 12's timeout work; Refund shipped without one.

### The solution
- **Payment-method tokens store a reference, never a card.** The request has no field that could
  carry a PAN — the only reliable way to guarantee one is never stored. The *response* has no field
  for the provider token either: that's the one value that could charge the card, the caller already
  has it, and echoing it would turn every list call into a way to harvest chargeable handles. Detach
  is a **timestamp, not a delete** (same reasoning as revoked API keys).
- **A refund timeout sweeper**, mirroring Payment's, but on a **deliberately longer timer — six
  hours** vs Payment's one. Before failing a refund, it **re-reads under a lock**: a callback may
  have settled the refund between the query and the write, and failing a refund the provider *just
  succeeded* is the worst outcome in this whole class.

### The trade-offs we weighed
- **The refund timeout is still a guess, and it can be wrong.** If the provider actually moved the
  money and just lost the callback, timing the refund out to `FAILED` means PayMesh believes no
  refund happened when one did — showing up later as a refundable balance that's too high and a
  second attempt that double-refunds. But the alternative — leaving it `PROCESSING` forever — holds
  head-room hostage on *every* lost callback, including the overwhelming majority where the provider
  genuinely never acted. So we fail it, on a long timer, and accept the residual risk. **This makes
  the gap tolerable; it doesn't close it.** (Post 20 is where it finally closes.)
- Two constraints on the token table mean **different** things and must report differently: the
  provider-token uniqueness is permanent (a provider handle is unique forever, detached or not), but
  the card-fingerprint index is *partial* — so re-attaching a card you removed needs a **fresh
  provider token**, which is what would really happen. Telling a caller "this card is already
  attached" when the real problem is a re-used handle sends them looking at a card that isn't there.
- **The row and its event must commit together** (a transactional-outbox rule) — missing in the
  first draft, caught in review. Without it, a crash between the two auto-commits leaves either a card
  on file nothing was told about, or an event announcing a card that doesn't exist.

### Final working & architecture
`payment_method_tokens` finally holds rows: attach (idempotency-registered, so a retried attach
whose first attempt committed replays instead of colliding on 409), list (reference only, never the
chargeable handle), detach (timestamp). A blocked customer cannot have a card attached. The refund
sweeper runs each row in its **own transaction** and *counts* errors rather than throwing — because
one unmappable row throwing would silently disable the other sweeps (a known bug shape we refused to
repeat).

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** attach a card → stored as an opaque provider reference → list shows it without the
  chargeable handle → detach stamps a timestamp. Refund happy path is unchanged; the sweeper only
  ever touches refunds stranded in `PROCESSING`.
- **Edge — late refund callback:** if a callback arrives *after* the 6-hour timeout, it finds the
  refund already `FAILED`, so it's **recorded but not applied** — on the record for whoever
  reconciles later, which is exactly what should happen.
- **Edge — re-attaching a removed card:** allowed, but only with a new provider token; the two
  uniqueness violations are reported as distinct errors.
- **Known-still-open (state honestly):** `UserStatus.SUSPENDED`/`CLOSED` are *still* unreachable
  after this post — because "who may disable a user" is a real question that deserves an answer, not
  a guess. That cliffhanger is Post 18. (Great sign-off line: *"I fixed the customer half. The user
  half needed a better question first.")*

### Image direction
**Concept:** an ADR page with a claim struck through, next to an empty database table.

Left: a document styled like a decision record, three bullet claims, the middle two ("A user can be
disabled", "A customer can be blocked") with a red strikethrough. Right: a database table icon
labeled `payment_method_tokens` with "0 rows · since V3" and a cobweb/dust motif. Between them, a
small caption vibe: *"the domain could say it; the system couldn't do it."*

**Prompt:**
> A dark-mode conceptual tech illustration, 16:9. On the left, a stylized "decision record" document
> card with a header "ADR-021: Consequences" and three bullet lines; the first bullet is in normal
> white text, the second and third bullets ("A user can be disabled", "A customer can be blocked")
> have a thin red strikethrough line. On the right, a minimalist database-table icon labeled
> "payment_method_tokens" with a small tag reading "0 rows · exists since V3" and faint cobwebs in
> its corners to imply it was never used. A thin teal dividing line between the two halves.
> Monospaced labels, near-black background, single red + single teal accent, clean, professional,
> no photorealism.

---

## POST 18 — The question was wrong

**Suggested title:** `PayMesh Devlog #18: "Who Can Disable a User?" Was the Wrong Question`

### What we built
The ability to disable people — split into **two operations at two scopes** that mean genuinely
different things, plus the grant/revoke pair that makes them usable. This finally makes the last
frozen enum in the platform (`UserStatus`) reachable.

### The problem we're solving
A departed employee's account could not be disabled — the last dead lifecycle enum. Post 17 left one
question open: *who may disable a user — platform staff, or a merchant admin over their own staff?*

### The problem we faced while developing
**The question assumed one operation. There are two, and conflating them is a security defect, not a
modelling preference.**

PayMesh has always supported one human holding roles at **several merchants** — the accountant who
serves two businesses is literally named in the code's docs. So:

- If "remove this employee" disabled the *account*, then **merchant A could lock somebody out of
  merchant B.** That's a cross-tenant action performed by a tenant — precisely what the tenancy model
  exists to prevent — and it would have arrived disguised as an obvious, reasonable feature.

The two operations:

| Operation | Scope | Who can | What survives |
|---|---|---|---|
| `DELETE /users/{id}/merchant-access` | one merchant | `MERCHANT_ADMIN` | the **account** (roles elsewhere untouched) |
| `POST /users/{id}/suspend\|close` | the whole platform | `PLATFORM_ADMIN` | nothing — barred everywhere |

### The solution
- The **merchant-scoped** operation removes `user_roles` rows for that tenant and leaves
  `UserStatus` alone. The **platform-scoped** one moves `UserStatus` and is *not* a tenant's to call.
- **Grant ships with revoke.** Revocation without a grant is one-way: a merchant that removed
  somebody by mistake, or re-hired them, would need PayMesh to intervene. An operation whose only
  undo is a support ticket is one an admin won't use confidently — and an admin who won't revoke
  access is the exact problem we're solving. `POST /users/{id}/merchant-access` grants a role at the
  caller's own merchant; it **cannot grant `PLATFORM_ADMIN`** (a tenant promoting someone to platform
  staff is the one escalation the whole role model exists to prevent), and it refuses a role the user
  already holds.

### The trade-offs we weighed
- **A subtle test bug we had to isolate.** Suspending a user also revokes all their sessions
  (`revokeAllForUser`). But the refresh path *already* re-reads the user and revokes the family when
  it can't authenticate — so suspension would bite at the next refresh anyway. That redundancy made
  our first test **worthless**: "a suspended user cannot refresh" passed whether or not the tokens
  were revoked, because the status re-check refused it either way. We **measured** it — deleting the
  revocation left the whole suite green. So we rewrote the test to assert on the *stored token rows*
  before any refresh, and now deleting the revocation turns exactly that one test red. Defense in
  depth is only real if a test proves the redundant layer is actually there.
- **Suspension isn't instant.** An already-issued access token still works for its remaining lifetime
  — at most 15 minutes, because nothing checks a denylist yet. Suspension is "within a quarter hour,"
  stated honestly.
- **Grant needs no consent from the user** (grant-by-id, not an invitation flow). Kept small by two
  things — ids are UUIDs so nothing can be enumerated, and the granted role reaches into the
  *granting* merchant's data, not the user's — and pinned by a test so closing it later is a
  deliberate change.
- **"No such user" and "holds no role at your merchant" return one identical 404** — telling them
  apart would let any merchant admin enumerate every user id on the platform.
- **Platform actions can't be made idempotent yet** — found by trying. An idempotency record is
  scoped by merchant and foreign-keyed to `merchants`; a platform action has no merchant, so
  registering one produced a foreign-key violation on a synthetic merchant id. The schema correctly
  refused to pretend a platform act belongs to a tenant. A retried suspend answers 409 instead of
  replaying — a real limitation, not a line on a to-do list.

### Final working & architecture
The platform-status filter now passes **any** caller holding `PLATFORM_ADMIN` (their authority is
platform-wide; the merchant they nominally sit at might be the very one they're about to suspend), so
the exemption list shrinks to just `/kyc-submissions`. This also retired a fragile earlier hack —
exempting bare verbs like `/activate` via `endsWith`, which would have exempted any future endpoint
that happened to end in one of those words. The four disable/enable actions get their own
`security_events` types: logging a suspension as `LOGGED_OUT` would be true and useless — the log
would show a *bar* as a *sign-out*.

### Happy-flow metrics, exceptions & edge cases
- **Happy path (merchant scope):** admin removes a departed employee's access at their own merchant;
  the person keeps their account and any roles at other merchants.
- **Happy path (platform scope):** platform staff suspend a human; every live session is revoked and
  the account is barred everywhere.
- **Edge — the two-merchant accountant:** removing them at merchant A leaves merchant B untouched.
  This is the whole reason for the split.
- **Edge — self-revoke:** a merchant admin may **not** revoke their own access (they're the only role
  that can grant it back — they'd lock themselves out).
- **Edge — last role lost:** allowed; the account survives with no tenant to act for (someone between
  assignments is a real state).
- **Edge — timing:** up to 15 minutes of access-token life remains after suspension.
- **Milestone line for the post:** *"Every lifecycle enum in the platform is now reachable. When the
  audit started, three of them weren't."*

### Image direction
**Concept:** one person, two different "disable" buttons, wildly different blast radius.

Center: a single user avatar/node connected to two merchant nodes (A and B). Left button "Remove from
Merchant A" only severs the A link. Right button "Suspend (platform)" greys out the whole person and
both links. Make the blast-radius difference the visual punchline.

**Prompt:**
> A dark-mode conceptual diagram, 16:9. Center: a single circular "user" node connected by two lines
> to two nodes labeled "Merchant A" and "Merchant B". Two action buttons below. The left button
> "Remove from Merchant A" is drawn cutting ONLY the line to Merchant A (a small scissors/cut mark on
> that one line), while the user and the Merchant B link stay fully lit in teal. The right button
> "Suspend — platform" is drawn greying out the entire user node and BOTH links at once, shown
> desaturated with a red tint. Clear visual contrast between a narrow cut and a total blackout.
> Monospaced labels, near-black background, teal + red accents, thin clean lines, professional, no
> photorealism.

---

## POST 19 — When an event can never succeed

**Suggested title:** `PayMesh Devlog #19: One Poisoned Event Froze an Account Forever`

### What we built
A **dead-letter budget** for the transactional outbox. When an event fails to be delivered too many
times, PayMesh stops retrying it, sets it aside (in place), and lets the rest of that account's events
flow again — and raises a health alarm.

### The problem we're solving
Since the event system was built, the relay retried a failed event **at the head of every pass,
forever**, deliberately holding back that aggregate's later events so ordering stays correct. That
was the safe choice for ordering — but it had a nasty residue, and we'd named it openly as "the
largest known hole in event delivery." Three things made it worse than it sounds:

1. **The freeze is per-aggregate and permanent.** One poisoned `payment.succeeded` means that
   payment's every later event is withheld indefinitely. **The Ledger never posts. The order never
   leaves PENDING.**
2. **It was silent.** The only signal was one WARN line per pass, on a platform with no log
   aggregation.
3. **The relay couldn't tell a first failure from a nine-hundredth.** Nothing was written down, so
   every pass started from zero knowledge — there was no way to *act* on "this will never work,"
   because the relay couldn't know it.

That third point is why the item stayed open for so long. "Add a dead-letter" sounds like a policy
decision; it was actually blocked on the relay **having a memory at all.**

### The problem we faced while developing
Choosing the shape. The obvious design is a second table you *move* failed events into. We rejected
it — the move is **a second copy of a fact this table already holds**, and two rows describing one
event can disagree. Moving the row also **loses its place in the ordering sequence**, so requeueing
it later would deliver an aggregate's events out of order — the exact guarantee the whole
freeze-the-aggregate behaviour exists to protect.

### The solution
**Count failures on the row, and stop claiming a row once the count hits a budget.**

- Four new columns on `outbox_events`: `attempt_count`, `last_attempt_at`, `last_error`,
  `dead_lettered_at`.
- The attempt that reaches the budget — **25 attempts, ≈ one minute at the 2-second relay
  interval** — stamps `dead_lettered_at`. The claim query gains `AND dead_lettered_at IS NULL`, so
  **the aggregate that event was blocking drains on the very next pass.**
- The increment and the decision are **one SQL statement**: `attempt_count + 1` appears in both the
  `SET` and the budget comparison, so the value compared is by construction the value being stored.
  Read-decide-write across two statements would leave a window where a second relay instance burns an
  attempt while recording none. The statement also carries `AND published_at IS NULL` — a
  compare-and-swap, so a delivery that *succeeded* between the failure and the bookkeeping write wins
  over a failure that's since been superseded. Without it, an event could be marked both delivered
  and abandoned.

### The trade-offs we weighed
- **The trade is stated without softening: a dead-lettered event is *never delivered*.** It's
  retained in place, in order, fully readable, and can be requeued by clearing the stamp. That is
  worse than delivery — and far better than an aggregate whose entire future is withheld because of
  one predecessor.
- **Why 25?** Read it as a duration, not a count: ~1 minute of continuous failure. That comfortably
  outlives the transient causes (a consumer restart, a lock timeout, a brief DB blip) while a
  genuinely poisoned event stops blocking its aggregate in about a minute rather than never. There is
  deliberately **no value meaning "retry forever"** — that was the old behaviour, and it was the bug.
- **The alert is a health indicator, not a metrics pipeline.** The design docs want
  Prometheus/Grafana; none of it exists, and reading "add an alert" as "first build an observability
  stack" is part of why this sat unfixed. `/actuator/health` is *already* what anything watching the
  app polls. A new indicator reports DOWN when dead-lettered events exist, or when the oldest
  deliverable event is older than a configured age (the only thing that distinguishes "keeping up"
  from "the relay stopped an hour ago" — a stopped relay is otherwise completely silent; only the age
  moves).

### Final working & architecture
`outbox_events` carries its own delivery memory; the entity is immutable and the columns are
maintained natively in SQL. Recording an attempt is **best-effort** — if the bookkeeping write itself
throws (most likely because the DB is down, which is also why delivery just failed), the pass
continues rather than aborting the sweep over every other aggregate. Failures surface three ways: one
ERROR log carrying the exact requeue statement, a `deadLettered` count on every pass, and the health
indicator that stays DOWN until an operator acts.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** an event is delivered on the first attempt; `attempt_count` stays 0, nothing is
  ever dead-lettered, health stays UP.
- **Transient failure:** a consumer restart or lock blip fails a few attempts; the event recovers
  well within the 25-attempt (~1 min) budget and delivers normally.
- **Poisoned event:** after ~1 minute it's dead-lettered; its aggregate drains on the next pass;
  health goes DOWN; an operator investigates and requeues by clearing the stamp (which preserves
  order, because the row never moved).
- **The sharp edge we recorded *before* an incident:** DOWN makes `/actuator/health` return 503 —
  intended, but this indicator must **never** be wired into a Kubernetes liveness/readiness probe.
  Restarting the process doesn't deliver a dead-lettered event, and draining an instance because its
  backlog is old removes the very process working through the backlog. It belongs in a health group
  that alerting scrapes and orchestration ignores.
- **Still open (state it):** outbox rows are never pruned; two events for one aggregate in the same
  instant have no defined order; there's a single relay instance with no leader election.

### Image direction
**Concept:** a conveyor of events, one stuck jamming everything behind it, then set aside so the line
moves.

Two-panel or before/after: **Before** — a queue where a red "poisoned" event sits at the head with a
long line of greyed events frozen behind it (a 🔒 on the whole lane). **After** — that red event
lifted onto a side shelf labeled "dead-letter · retained in place," a counter "25 attempts," and the
green line flowing again. A small `/actuator/health: DOWN` badge.

**Prompt:**
> A dark-mode conceptual systems diagram, 16:9, split into a "before" and "after" comparison. LEFT
> ("before"): a horizontal queue of rounded event cards; the leftmost card is red and marked
> "FAILED", and every card behind it is greyed out and frozen, with a lock icon over the whole lane
> and a label "aggregate frozen — forever". RIGHT ("after"): the same red card lifted up onto a small
> side shelf labeled "dead-letter (retained, in order)" with a small counter "25 attempts", while the
> remaining cards are now teal and flowing with a forward arrow labeled "drains next pass". A small
> status badge reads "/actuator/health: DOWN". Monospaced labels, near-black background, red + teal
> accents, thin clean lines, professional, no photorealism.

---

## POST 20 — Trust, but verify

**Suggested title:** `PayMesh Devlog #20: I Marked a Real Payment as Failed`

### What we built
Reconciliation: a scheduled job that fetches the provider's own daily record and **replays every
terminal row through the exact same callback path a live provider callback uses** — so a payment
PayMesh *guessed* had failed can be revived by the provider's own truth. This is the post where the
"the timeout is a guess" thread from Posts 12 and 17 finally gets resolved.

### The problem we're solving
Two earlier features time things out on a guess: a stranded `PROCESSING` payment is failed after an
hour with **no evidence it failed**, and (Post 17) a stranded refund after six. Both named
"reconciliation against the provider's own record" as the real answer — and that job didn't exist.
The provider had been producing its own daily truth the whole time, and **nothing read it.** The cost
isn't an untidy row: when the provider actually collected but the callback was lost, PayMesh holds a
`FAILED` payment, the Ledger never posts, and **the merchant is simply short — permanently, and
silently.**

### The problem we faced while developing
The tempting design is to **diff**: read both sides, work out the difference, apply it. We rejected
it, and that rejection is the central decision. A diff would be **a second copy of the money-path
transition rules** — the payment aggregate already refuses illegal transitions, already refuses an
amount it didn't authorize, already judges staleness, already writes the attempt row and the outbox
event that makes the Ledger post. A job re-deriving any of that would **diverge the first time one of
those rules changed, and nothing would notice.**

Then two things surfaced in review that made this genuinely hard:

1. **Making a `FAILED` payment revivable reopened the "one live intent per order" hole.** The sweeper
   fails a stranded intent *precisely so* the order's slot is released and the merchant can retry. So
   the normal sequence is: intent A times out → merchant opens intent B on the same order → *then* A's
   provider finally speaks. Reviving A shoves it back into a unique index that B now occupies →
   Postgres unique violation → a 500 on the callback path → which the provider retries **forever** →
   and the rolled-back transaction takes the callback record with it, so the refusal leaves **no
   trace at all.**
2. **A revived intent still carried its old failure reason** (`provider_no_response`), so a collected
   payment could reach `SUCCEEDED` while any caller branching on `failureCode != null` still read it
   as failed.

### The solution
- **Replay, don't diff.** No comparison against PayMesh's state anywhere in the job. Each terminal
  row from the provider's report goes through the same callback service, and the aggregate decides
  what — if anything — changes.
- **The dedup id is the entire safety argument.** The job mints `recon:` + `SHA-256(kind | id |
  terminal-state | amount)`:
  - **Deterministic**, so re-running a day (nightly, or an operator by hand) is a `DUPLICATE`, not a
    second application. Without this, a nightly job re-applies the same outcome indefinitely — on the
    capture path, that's collecting twice.
  - **Prefixed**, so it can never collide with a real callback's id — a collision would swallow the
    replay as a duplicate and leave a real divergence unrepaired, silently, looking exactly like
    success.
  - Including the terminal values in the hash lets a row that genuinely *changed* since last run (an
    authorization later captured) be replayed again instead of mistaken for the same fact.
- **Exactly one terminal state stops absorbing late callbacks**, guarded narrowly by
  `isUnansweredTimeout()`: only `status == FAILED && failureCode == provider_no_response`. That code
  is written by one caller and means "nobody answered" — a payment the provider *declined* carries
  the provider's own code and stays terminal forever. Applying a confirmed outcome overwrites the
  guess-failure code, taking the payment back out of the revisable window. The monotonic event-clock
  still applies on top.
- For the reopened order-slot race, the callback service now **pre-checks** whether the order already
  has a live intent and refuses the revival as `IGNORED_TERMINAL` — **pre-checked, not caught**,
  because catching the exception would be too late (it already killed the transaction holding the
  callback record). **The newer intent wins**, and the ignored row is recorded as a genuine money
  divergence somebody must look at (this provider claims it collected against an intent PayMesh has
  superseded).

### The trade-offs we weighed
- **Replaying every row every run** is the accepted cost — including the overwhelming majority that
  were already correct, each just one indexed lookup answering `DUPLICATE`. The bound is the day, not
  the table.
- **The window is 3 days, not 1.** A callback can be late by more than a day, a file can be amended,
  a nightly pass can fail to run — with a window of one, any of those skips a day forever.
- **This reading is specific to the simulated provider's file.** `TIMED_OUT` here carries a captured
  amount of 0, so replaying it as `FAILED` confirms the guess. A **real** acquirer might report an
  outcome it doesn't yet fully know, and "unknown" must never be read as "nothing moved." Any status
  the job doesn't recognise is **skipped, never defaulted** — every value in that switch moves money.
- **The fetch is HTTP over loopback to a service in the same JVM** — which looks absurd, but a direct
  Java call would make the "provider" a compile-time dependency of the money path and lock
  reconciliation to exactly one provider forever (the one that can never be real). The loopback hop
  buys a single adapter that changes when the provider becomes an SFTP drop or a signed CSV, and it
  exercises the real serialization, auth filter, and error paths.

### Final working & architecture
A scheduled job pulls the provider's report for a 3-day UTC window and replays terminal rows through
the real callback service. Deduplication is by the deterministic `recon:` hash; the provider's own
`updatedAt` travels as the event's `occurredAt` (never the job's clock, or reconciling an old day
would overwrite a newer callback). The revive path and normal late-callback path share one entrance
to the state machine — deliberately, so there's no second, reconciliation-only door that bypasses the
amount check, staleness guard, attempt row, and outbox event.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** the vast majority of rows are already consistent → each answers `DUPLICATE` /
  `ALREADY_CONSISTENT`, nothing moves. A run over a clean day reports "N examined, 0 repaired."
- **The payoff case:** a payment PayMesh timed out to `FAILED`, that the provider actually collected,
  is replayed → reaches `SUCCEEDED` → **the Ledger finally posts its balance.** The guess becomes a
  confirmation.
- **Edge — superseded intent:** the merchant already retried on a fresh intent → the stale revival is
  refused as `IGNORED_TERMINAL` and recorded as a real divergence for a human, newer intent wins.
- **Edge — revived intent's stale fields:** forward transitions now clear `failureCode`/
  `failureMessage`, so a revived payment never reports itself as failed. (This also quietly fixes
  late callbacks, not just reconciliation — same fact, same authority, one entrance.)
- **Edge — provider unreachable:** this is the **one** failure that propagates instead of being
  counted. A report that was never read has repaired nothing; returning an empty report would show
  "0 examined, 0 repaired" — identical to a genuinely quiet day. A provider down for a week would
  otherwise report a clean reconciliation every night. Connection refused, a 500, and an unparseable
  body all raise it.
- **Known imprecision, recorded not hidden:** a settled refund and a nonexistent refund are
  indistinguishable to the adapter, so both count as `ALREADY_CONSISTENT`; `REPAIRED` is exact either
  way.
- **Reflection line:** *"The scariest bugs in payments aren't the ones that crash. They're the ones
  where every request was individually correct, and the money is quietly wrong anyway."* (ties all
  the way back to Post 14).

### Image direction
**Concept:** two ledgers side by side — PayMesh says FAILED, the provider says COLLECTED — and a
"replay" arrow that reconciles them, not a diff.

Left column: PayMesh's view of a payment, stamped red `FAILED`. Right column: the provider's daily
report, same payment, stamped green `COLLECTED ₹1,000`. A curved arrow labeled "replay through the
same callback path" loops the provider's row back into PayMesh, flipping the left stamp to
`SUCCEEDED` and lighting up a small "Ledger posted" indicator. Emphasize *replay*, not *compare*.

**Prompt:**
> A dark-mode conceptual finance-systems diagram, 16:9. Two vertical panels. LEFT panel titled
> "PayMesh" shows a payment row stamped in red "FAILED (no provider response)". RIGHT panel titled
> "Provider daily report" shows the same payment row stamped in green "COLLECTED · ₹1,000". A bold
> curved arrow labeled "replay through the same callback path" flows from the provider's row back
> into the PayMesh panel, and the left stamp is shown transitioning from red "FAILED" to teal
> "SUCCEEDED", with a small lit indicator reading "Ledger posted". Do not depict a subtraction or
> diff — depict a replay/flow. Monospaced labels, near-black background, red + green + teal accents,
> thin clean lines, professional, no photorealism.

---

# ==================================================================
# SEASON 2 — PHASE 2: EVENT-DRIVEN CAPABILITIES (Posts 21–29)
# ==================================================================

## POST 21 — The admin who couldn't exist

**Suggested title:** `PayMesh Devlog #21: The Chicken-and-Egg That Locked the Whole Platform`

### What we built
Made platform-wide roles (`PLATFORM_ADMIN`) grantable — by letting a role be held with **no merchant
at all** — plus a bootstrap path to create the very first one. Until this, only two of four roles
could ever be produced, and the platform had a silent deadlock at its core.

### The problem we're solving
Follow the chain: (1) a merchant registers → lands on `PENDING_VERIFICATION`; (2) the status gate
(Post 15) refuses every write until they're `ACTIVE`; (3) activation is `PLATFORM_ADMIN`-only; (4) **no
`PLATFORM_ADMIN` could exist**, because `user_roles.merchant_id` was `NOT NULL` and part of the
primary key, so a platform-wide grant had nowhere to live. A merchant registered through the public
endpoint could **never** be activated. The only reason the platform seemed usable at all was that the
Postman collection minted its own dev-signed token — a workaround that doesn't survive a real
deployment, and one Phase 2 would have had to inherit eight more times.

### The problem we faced while developing
Making the column nullable *by itself* would have shipped a **privilege escalation**.
`requirePlatformAdmin()` read the claim as `PLATFORM_ADMIN:<any merchant>` — it scanned every merchant
scope for the role. That was safe *only because no endpoint could grant it*. The moment one could, a
`MERCHANT_ADMIN` granting themselves `PLATFORM_ADMIN` at their own merchant would become platform
staff — able to lift their own suspension. And a second, sneakier bug: moving platform roles out of
the per-merchant map **silently broke the status filter's** own copy of that scan, so the first
bootstrapped admin (who also holds `MERCHANT_ADMIN` at an un-activatable merchant) hit the exact
deadlock this ADR removes, one layer up.

### The solution
- **A platform role is held with `merchant_id = NULL`, and the database refuses every other shape.**
  Drop the primary key, make the column nullable (`NULL` means platform-wide — not "unknown", not
  "any"), replace the PK with **two partial unique indexes** (one for merchant-scoped rows, one for
  platform rows — two, not one, because in SQL `NULL <> NULL` so a single index would admit duplicate
  platform grants), and a biconditional CHECK: `PLATFORM_ADMIN` **if and only if** `merchant_id IS
  NULL`.
- **The claim separator is the scope.** `MERCHANT_ADMIN:mrc_...` is held at that merchant;
  `PLATFORM_ADMIN` with no colon is platform-wide. A token literally **cannot express** "platform
  admin at one tenant."
- **The first admin comes from a startup property** (`bootstrap-platform-admin-email`) that *promotes
  an existing account*, never creates one — because a seeded user means a password hash committed to
  git, and inventing a password means a privileged account with a credential nobody chose. It runs
  only while the platform has zero admins.

### The trade-offs we weighed
- **The escalation is closed in three independent layers** — database CHECK, domain (`User.grantRoleAt`
  won't build it), and claim parser — because the failure mode is a tenant escaping its tenancy, and
  the *application* check is the one a future refactor can delete by accident. An integration test
  inserts straight through JDBC, past the top two layers, to prove the constraint holds alone.
- **Demotion of the last admin is refused by a `FOR UPDATE`-locked count, not a CHECK.** A count-then-
  delete is check-then-act: under READ COMMITTED, two overlapping demotions of the last two admins each
  read "2 left" and both commit, reaching the dead end through the ordinary API with two clicks. A
  CHECK sees one row; a deferred trigger runs under its own snapshot and both still pass. **Serializing
  the readers is the only thing that works** — so it locks every platform-admin row and the second
  demotion waits, then counts what's really left. (Same lesson as the refund race in Post 14: some
  invariants can only be held by making requests take turns.)

### Final working & architecture
`AuthenticatedCaller` gains a third component, `platformRoles`, separate from `rolesByMerchant`,
because a platform role has no merchant to be keyed by. Both the authorization check and the status
filter now read `isPlatformAdmin()` through one method — the lesson being that two readers of one fact
eventually disagree, silently. Promotion and demotion both end every live session (promotion so the
new claim is reissued immediately; demotion so the old one stops), each with its own security-event
type. The Postman collection now bootstraps a **real** admin and activates through it — walking the
same path a real integrator walks.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** operator sets the bootstrap email, restarts → that account becomes `PLATFORM_ADMIN` →
  it can now activate merchants → the whole platform is usable end-to-end for the first time without a
  hand-minted token.
- **Edge — last-admin protection:** demoting the only (or second-to-last) admin is refused under a
  row lock, even under concurrent requests.
- **Edge — bootstrap race:** two instances booting the same email at once → the loser's insert fails
  on the platform-scoped unique index, that instance fails to start, and boots clean on retry (an
  admin now exists).
- **Edge — token lag:** a demoted admin's already-issued access token still works for ≤15 minutes
  (no revocation list); ending the refresh-token family is what makes demotion stick.
- **Known gap:** `SERVICE_ACCOUNT` is still not grantable — but now because machines authenticate with
  merchant-scoped API keys (Post 16), not because the table can't hold the role.

### Image direction
**Concept:** a circular dependency deadlock, then broken by a `NULL`.

Draw a four-node cycle: "Merchant PENDING" → "needs activation" → "needs PLATFORM_ADMIN" → "needs a
grant" → "needs a PLATFORM_ADMIN to grant it" looping back. Center it with a red circular arrow
(deadlock). Then a break-out: a single `user_roles` row with `merchant_id = NULL` highlighted, snapping
the loop.

**Prompt:**
> A dark-mode conceptual diagram, 16:9. Left: four labeled nodes arranged in a ring with red arrows
> forming a closed loop — "Merchant: PENDING", "needs activation", "activation needs PLATFORM_ADMIN",
> "granting PLATFORM_ADMIN needs a PLATFORM_ADMIN" — with a bold red circular arrow in the center
> implying a deadlock. Right: a single highlighted database row card labeled "user_roles" showing
> columns "user_id, merchant_id = NULL, role = PLATFORM_ADMIN", glowing teal, with a small arrow
> breaking the red loop. Monospaced labels, near-black background, red + teal accents, thin lines,
> professional, no photorealism.

---

## POST 22 — A secret with nowhere to leak from

**Suggested title:** `PayMesh Devlog #22: I Built Webhooks Without Storing a Single Secret`

### What we built
Merchant webhooks: PayMesh POSTs a signed payload to a merchant's URL when something happens on their
account (`payment.succeeded`, `payment.failed`, `refund.succeeded`, `order.paid`), with HMAC
signatures, retries with backoff, rotation, and replay. The headline decision: **the signing secret is
never stored anywhere.**

### The problem we're solving
Phase 1 built an entire event backbone — outbox, relay, dispatcher, inbox, dead-letter budget — and
every merchant-facing event reached an **empty handler list.** A merchant learned their payment
succeeded by *polling*. Webhooks are the capability that makes the backbone visible from outside the
process.

### The problem we faced while developing
A signing secret is a genuinely hard storage problem. The API-key pattern from Post 16 looks like the
precedent but isn't: that stores a **hash**, which works because you only ever need to *verify* an
inbound secret. **A signing secret is the opposite** — PayMesh must *reproduce* it to stamp every
outgoing payload, so a hash is useless, and the choice is between reversible (encrypted) storage and
something cleverer. And encryption here isn't a column, it's a whole subsystem: there's no cipher, no
key management, no encrypted column type anywhere in the codebase.

### The solution
**Derive the secret, never store it:**

```
secret = "pmsec_" + Base64Url( HMAC-SHA256(masterKey, "paymesh.webhook.v1|" + endpointId + "|" + version) )
```

The endpoint row holds a `secret_version` integer where the secret would have been. **Rotation is just
incrementing that integer.** There is no ciphertext, no decrypt path, and nothing in the database whose
leak lets an attacker sign as PayMesh. The clinching argument: a leaked master key exposes every
secret — but so does a leaked encryption key that unwraps every stored secret. **Identical worst
cases**, and one of them makes you build, review, and rotate a cipher subsystem. The only thing given
up is letting a merchant supply their *own* secret — which Stripe, GitHub, and Shopify all decline to
offer too.

### The trade-offs we weighed
- **A known-answer test vector is part of the decision.** The day someone "tidies" the `info` string,
  every merchant's verifier starts rejecting every delivery — and nothing in the suite notices,
  because every test would derive with the same changed formula. A pinned vector (master key →
  endpoint → exact expected `pmsec_...` output) fails loudly if the formula ever drifts.
- **The prefix started as `whsec_` and had to change to `pmsec_`.** `whsec_` is Stripe's, and it's what
  an integrator recognizes. But GitHub's secret scanner recognizes it too — within minutes of the
  first push it opened "Stripe Webhook Signing Secret" alerts against the *test vectors*. A prefix
  exists to be recognized; one that gets a PayMesh secret recognized as *someone else's* is failing at
  its only job. (Great, specific, human detail for the post.)
- **The retry horizon shipped wrong — and it was silent.** The schedule is 1m/5m/30m/2h/6h — six
  attempts over **8h36m**. It shipped as **2h36m** because `MAX_ATTEMPTS` was set to the number of
  *waits* (5), so delivery was declared dead on the fifth attempt and the six-hour wait was never
  reached. Every doc quoting the figure was right and the code was wrong. A test now asserts the total
  horizon as one number so the two can't drift again.
- **Three tables, not the four the plan reserved.** No `webhook_delivery_attempts` table — the delivery
  row's counters answer what a merchant debugging a failure actually asks; a per-attempt table is "a
  log wearing a table's clothes."
- **The payload column is `TEXT`, not `JSONB`.** Replay must resend the *same bytes* (the merchant
  recomputes an HMAC over the body; one character of drift fails it), and JSONB normalizes whitespace,
  key order, and duplicate keys. Byte-identity forces `TEXT` behind an immutability trigger.

### Final working & architecture
The event handler only ever **writes rows** — one frozen `webhook_events` payload (shared by every
subscribed endpoint) and one `PENDING` delivery per active endpoint — because the invariant is **a
merchant's endpoint being down must never affect a payment**, which means *no outbound HTTP inside the
dispatcher's transaction, ever.* A separate scheduled dispatcher sends later, one transaction per row,
claiming with `SKIP LOCKED`. Fan-out is bounded (max 20 endpoints per merchant) so the money-path
handler has a ceiling. Rotation overlaps for 24h and the outbound header carries **two** `v1=`
signatures during the window so a merchant eats no failures between rotating and deploying their new
verifier.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** event → one frozen payload + N pending deliveries → dispatcher signs and POSTs →
  merchant verifies the HMAC → 2xx → `SENT`.
- **Retry:** non-2xx → backoff 1m/5m/30m/2h/6h, six attempts, then `FAILED` (8h36m total). An endpoint
  is disabled after **20 consecutive dead deliveries** (120 failed HTTP requests); any success resets
  the counter.
- **SSRF is the biggest exposure here — and it's not the secret.** A merchant supplies a URL and
  PayMesh's server POSTs to it. `https://127.0.0.1:...` reaches inside the trust boundary;
  `https://169.254.169.254/` is a cloud metadata endpoint. Defenses: reject userinfo URLs at
  registration; resolve and reject loopback/link-local/RFC1918/IPv6-ULA **at send time**, not just
  registration; `Redirect.NEVER` (a 302 to a metadata address defeats every check); connect/read
  timeouts; a 4 KiB read cap. Known-open: the resolve-then-connect TOCTOU window is narrowed, not
  closed — recorded, not claimed solved.
- **Create and rotate deliberately bypass the idempotency filter** — that filter persists the whole
  response body to replay it, and these responses *contain the secret*, so filtering them would write
  a plaintext secret to `idempotency_records` — in the same DB whose dump the derivation scheme exists
  to make useless. A lost create response is recovered by **rotating**, not retrying.

### Image direction
**Concept:** the "empty vault" — where a stored secret would be, there's just a version number and a
formula.

Show a merchant endpoint row. Where other systems draw a locked vault holding `whsec_...`, draw an
**open, empty vault** with just `secret_version: 3` inside, and a formula card floating beside it:
`HMAC(masterKey, "…|endpointId|3")`. A speech-bubble/arrow shows the secret being *computed on demand*
at send time, never at rest.

**Prompt:**
> A dark-mode conceptual security diagram, 16:9. Center-left: a database row card labeled
> "webhook_endpoints" whose "secret" cell is drawn as a small OPEN, EMPTY vault containing only the
> text "secret_version: 3". Center-right: a floating formula card reading "secret = HMAC-SHA256(master
> key, 'paymesh.webhook.v1 | endpointId | 3')" glowing teal, with an arrow labeled "derived at send
> time, never stored" pointing from the formula to an outgoing signed request envelope. A small red
> label "nothing here to leak" points at the empty vault. Monospaced text, near-black background,
> single teal accent, thin clean lines, professional, no photorealism.

---

## POST 23 — One bad row, five dead jobs

**Suggested title:** `PayMesh Devlog #23: The Malformed Row That Could Silently Kill Five Background Jobs`

### What we built
A database-level guarantee that every identifier PayMesh mints actually has the shape the application
expects — **63 CHECK constraints across 20 ID types**, enforced by one reusable SQL function. Small
migration, disproportionately important story.

### The problem we're solving
Every public ID in PayMesh is `<prefix>_<uuid>` (`mrc_...`, `pi_...`, `ref_...`), and every ID value
object enforces that in Java — it throws on anything malformed. **The database never agreed.** Every
one of those columns was a bare `VARCHAR`, so PostgreSQL would happily store `not-a-merchant-id` — an
identifier the application can then never read back.

### The problem we faced while developing
This wasn't theoretical, and it was found the expensive way. There were five scheduled sweeps (order
expiry, payment timeout, refund timeout, abandoned-checkout cleanup, the simulator's dispatcher) that
mapped candidate rows **outside** their per-item `try/catch`. It had been filed as latent — "needs
database state the current CHECKs forbid." That premise was wrong: nobody had checked whether the IDs
were actually constrained, and **none of them were.** A single malformed ID would have disabled all
five sweeps — **permanently and silently**, because the bad row sorts first in every subsequent batch,
so the job re-hits it and dies before touching anything else.

### The solution
One `IMMUTABLE` SQL function, `is_prefixed_id(value, prefix)`, called by all 63 constraints — rather
than 63 inline regexes, because inlining is 63 chances to fat-finger a character class in a way nothing
catches (a subtly-wrong regex still accepts every ID the app mints, and only misbehaves on the
malformed row the constraint exists to reject). `STRICT` so `NULL` in → `NULL` out (a legitimately
absent ID is not a malformed one).

### The trade-offs we weighed
- **Writing the constraint surfaced that the domain type was lying about its own invariant.** Fifteen
  of eighteen ID types round-tripped with `equalsIgnoreCase`, so `mrc_550E...` and `mrc_550e...` were
  *both* legal — two spellings of one UUID, i.e. two rows for one thing on a primary key. Three others
  called `UUID.fromString` and threw the result away, which also admitted padded shorthand like
  `apc_1-1-1-1-1`. The constraint was *stricter* than the type it mirrored, and **the constraint was
  the correct half** — Java was fixed to match it. Nothing else would ever have found this: every ID
  the app mints is canonical, so no test and no production traffic produces the divergent case.
- **Five categories are deliberately *not* constrained**, and the migration names each so a future
  reader knows they were excluded, not missed — polymorphic columns (`outbox_events.aggregate_id` can
  be `ord_`/`pi_`/`ref_`/`cus_`; a CHECK would rot every time a new aggregate emits an event),
  provider-supplied IDs (we don't mint them), and merchant-supplied free text.
- **The honest limit, stated up front: this does *not* make row mapping safe.** The per-item
  `try/catch` stays load-bearing. `orders.metadata` is JSONB with no shape constraint — a JSON *array*
  there is still an unmappable row. We deliberately **didn't** chase every unmappable shape with a
  constraint: "the boundary is what makes the system survive the row you didn't predict; a constraint
  is defense in depth *behind* it, never a replacement."

### Final working & architecture
V26 adds the 63 constraints; it applied against a populated database with **zero** violating rows, so
no data repair was needed. Writing it even found a *sixth* instance of the same "unmappable row kills
the job" bug — the relay itself deserialized a JSONB payload inside the repository call, outside the
per-item try — fixed the same way the five sweeps were (select `payload::text`, parse inside the
caller's try). The pattern worth naming in the post: **"I fixed every instance" is a claim about a
search, and a search nobody re-ran is a claim nobody checked.**

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** every ID the app mints is canonical, so the constraints are invisible in normal
  operation — they only ever reject a row that should never have existed.
- **Edge — the defense triggers:** a malformed ID (from raw SQL, a bad migration, a future bug) is now
  refused at write time with a readable constraint violation, instead of silently poisoning a sweep's
  next batch.
- **Edge — case collision:** two casings of one UUID are now one canonical value; the old double-row-
  on-a-PK hole is closed.
- **Reflection line:** *"A constraint I could predict is defense in depth. The `try/catch` at the
  boundary is what survives the row I couldn't."*

### Image direction
**Concept:** a conveyor of jobs jammed by one malformed row that sorts to the front.

Five labeled job lanes (order expiry, payment timeout, refund timeout, checkout cleanup, sim
dispatcher) all feeding from one queue whose **first** card is a red malformed ID `mrc_not-a-uuid`.
All five lanes are frozen behind it. Overlay a `CHECK (is_prefixed_id(...))` stamp rejecting the bad
card at the door.

**Prompt:**
> A dark-mode conceptual systems diagram, 16:9. A single input queue of rounded ID cards feeds five
> parallel horizontal job lanes labeled "order expiry", "payment timeout", "refund timeout", "checkout
> cleanup", "simulator dispatch". The leftmost card in the queue is red and reads "mrc_not-a-uuid",
> and all five lanes behind it are greyed out and frozen with small lock icons. A teal stamp labeled
> "CHECK is_prefixed_id()" is shown rejecting that red card at the queue entrance. Monospaced labels,
> near-black background, red + teal accents, thin clean lines, professional, no photorealism.

---

## POST 24 — Risk decides, Payment acts

**Suggested title:** `PayMesh Devlog #24: The Fraud Check That's Not Allowed to Fail a Payment`

### What we built
Synchronous risk evaluation: before a payment is confirmed, a risk check runs and returns a decision.
The load-bearing rule is a separation of powers — **Risk decides, Payment acts.** Risk writes nothing
to any payment table, has no opinion about status, and emits no event.

### The problem we're solving
A payment platform needs to be able to refuse a suspicious payment. But *how* that check is wired
matters enormously: a Risk component that could directly fail a payment would be a **second author of
Payment's state machine** — and two authors of one state machine is how a status becomes
unexplainable. So the design question isn't "how do we score risk," it's "how do we add a decision-
maker without adding a second writer of the money path."

### The problem we faced while developing
Two defects the unit tests **structurally could not find** — both needed a real database and a real
transaction:

1. **The velocity count included the payment it was judging.** The intent being scored is created in
   the same window moments before confirm, so a naive count always returned the subject of the
   question — every payment scored one too high, every threshold fired early. The unit test stubbed the
   count with a hand-set number, so the real predicate never ran. It took an integration test asserting
   a fresh customer's first confirm scores **zero**.
2. **The assessment didn't survive a block.** The evidence was written *inside* the confirm's
   transaction — and a `BLOCK` makes that transaction throw, so the assessment **rolled back with the
   confirm it refused.** The merchant got a 422 naming an assessment ID that pointed at a row that
   didn't exist. No unit test can see this — they have no real transaction to roll back.

### The solution
- **Evaluate Risk *before* Payment opens its transaction.** The first fix attempt was `REQUIRES_NEW`
  on the save — and it was the wrong answer: Spring suspends the outer transaction **without releasing
  its connection**, so every confirm would hold two pool connections at once while a row lock is live.
  The default pool is 10 → ~5 concurrent confirms wedge the entire pool. The right fix was to **stop
  nesting**: evaluate risk before the transaction opens, so the assessment is enclosed by nothing and
  commits on its own, one connection at a time. Reading the intent unlocked is safe — every feature
  Risk looks at (amount, currency, customer) is fixed at creation.
- **A blocked payment is *refused, not failed*** → 422, intent stays `REQUIRES_CONFIRMATION`. A
  denylist entry is a live opinion an operator can retract (entries carry an expiry), so burning the
  intent for a decision reversible in a minute is the harsher default. The merchant retries after the
  entry is removed.

### The trade-offs we weighed
- **Three things from the plan were deliberately *not* built:** a `risk_rules` table of stored
  expressions (needs an evaluator and a syntax, and a bad expression takes the money path down at
  *runtime* rather than compile time — rules are **code**, versioned by a `VERSION` constant), Redis
  velocity counters (a whole outage mode on the money path for a count PostgreSQL already computes from
  rows it has), and a review queue (nothing works one yet). Dropping Redis also *removes* the fail-open
  requirement rather than skipping it — with no second system, there's nothing to be down.
- **Reproducibility without a rules table:** a historical decision must be explainable, so every
  assessment stores the **inputs** and the **ruleset version** verbatim. A decision re-derived from a
  live rule table isn't evidence of what happened — it's a guess about what would happen *now*.
- **The error body names the assessment, never the rule.** An error that says *which* rule refused a
  payment is a free oracle: retry, vary one input, watch the message change, and you've mapped the
  ruleset. Support answers "why?" from the database, where the reasons belong.
- **Denylist values are unsalted-SHA-256 hashed** — because the only operation is an equality match and
  a salt makes it unlookupable. Honest about the limit: it resists a table dump and idle browsing, not
  an attacker with a candidate list.

### Final working & architecture
`RiskCheck.Decision` carries a boolean and an assessment ID — the matched rules stay on Risk's side of
the module boundary by construction, not discipline. The assessment records that an *evaluation
happened*, not that the payment proceeded (which is why it carries no payment status). Building this
also exposed a hole in the module-boundary test: its list of capabilities didn't include `risk`, so a
new adapter reaching straight into Payment's table wasn't caught — *"a new capability is precisely when
that list is stale, and precisely when nobody thinks to check it."*

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** confirm → risk evaluates before the transaction opens → `ALLOW` → confirm proceeds
  normally; a fresh customer's first payment scores zero.
- **Block path:** `BLOCK` → 422 `PaymentBlockedByRiskException`, intent stays confirmable, the
  assessment row **survives** (it's outside the rolled-back transaction); operator retracts the
  denylist entry, merchant retries, it works.
- **Edge — velocity:** counts intents *created* in the window (an abandoned checkout is still part of
  the pattern), excluding the intent being judged.
- **Edge — pool safety:** evaluation holds exactly one connection; the `REQUIRES_NEW` two-connection
  trap is avoided by placement, not configuration.
- **Deliberate no-op today:** `REVIEW` and `ALLOW` behave identically (the difference is recorded
  evidence) — a blocking `REVIEW` while nothing works a queue would strand the payment forever, which
  is worse than the risk it hedges.

### Image direction
**Concept:** separation of powers — a judge who rules but never touches the machine.

Left: a "Risk" node labeled *decides* holding a verdict card (ALLOW / BLOCK) — with a clear barrier
showing it has **no arrow** into the payment tables. Right: a "Payment" node labeled *acts*, the only
one with a write-arrow into the state machine. The verdict passes as a small boolean+id token across
the barrier.

**Prompt:**
> A dark-mode conceptual architecture diagram, 16:9. Left node labeled "Risk — decides" holds a small
> verdict card reading "ALLOW / BLOCK" and an assessment id; a solid vertical barrier line separates it
> from the right. Right node labeled "Payment — acts" has the ONLY arrow writing into a stack of boxes
> labeled "payment state machine". Across the barrier passes a single small token labeled
> "{ allowed: bool, id }". A faint red crossed-out arrow shows Risk NOT writing to the payment boxes.
> Monospaced labels, near-black background, teal accent with one red crossed-out arrow, thin clean
> lines, professional, no photorealism.

---

## POST 25 — When is money actually a merchant's?

**Suggested title:** `PayMesh Devlog #25: The Ledger Decides When Money Becomes Settleable`

### What we built
A merchant's balance becomes **settleable**: a scheduled job posts a balanced ledger transaction per
payment once its funds clear the merchant's holding period, moving money from `MERCHANT_PENDING` to
`MERCHANT_AVAILABLE`. This is the prerequisite for actually paying merchants out (Post 26).

### The problem we're solving
Captured money isn't instantly the merchant's to withdraw — there's a holding period (chargeback risk,
etc.). So the platform needs a notion of "available" balance distinct from "pending." The design
question: where does the truth of "how much is available" live, and how does the job that moves money
between them avoid the usual double-count and race bugs?

### The problem we faced while developing
Two subtle things. First, a partial refund breaks the naive approach: capture ₹10,000, refund ₹3,000,
then release the **gross** ₹10,000 → pending sits at −₹3,000 and available at ₹10,000, and available is
what gets paid out — so the merchant is paid ₹10,000 for a payment worth ₹7,000 to them. Second, an
integration test caught a constraint the *entire rest of the stack* agreed about and the database
didn't: there are **two** CHECKs naming account types (one for which carry a merchant, one for which
exist at all), the migration taught only the first about the new account type, and the first real
release failed on the second — findable *only* by a test that actually opened the account.

### The solution
- **The release job carries no state table.** Two facts the ledger already holds answer everything:
  "has this payment been released?" → a unique idempotency key `funds-released:pi_x`; "how much is
  left?" → the signed sum of pending-account lines across every journal referencing that payment. A
  state table would be a second copy of both, and the failure mode of a second copy is that it
  disagrees with the first. Bonus: a released payment **sums to zero** (its own release journal is part
  of the sum), so the job is idempotent in *arithmetic* as well as by key — three runs post one
  journal.
- **Refund reversals now reference the *payment*, not the refund** — the one change to an existing
  journal's shape, and what makes the per-payment sum move the **net** position. (It turned out the
  refund event already carried the payment ID; the reversal just needed to point at it.)

### The trade-offs we weighed
- **`availableMinor` may go *negative*.** A merchant refunding after being paid out genuinely owes
  PayMesh the difference, and a payment platform has to be able to say so. Clamping at zero would be a
  second copy of the truth that disagrees with the ledger entries.
- **It's a journal, not a status flag** — the two liabilities are separate accounts, so the move
  between them is visible, dated, and reversible like anything else in the ledger. (Same philosophy as
  the whole ledger: correct with new entries, never edits.)
- **One column of config, not five.** `settlement_configs` carries only the holding period — the
  schedule, minimum, currency, and payout account belong to Settlement (Post 26), which doesn't exist
  yet, and *a column whose meaning is decided by a capability that doesn't exist is a column that gets
  it wrong.* The holding period is stored as **integer seconds, not a PostgreSQL `INTERVAL`** — an
  `INTERVAL` can mean "1 month," and a holding period whose length depends on which month you ask in
  can't be reasoned about or reproduced in a test.
- **The platform default is a value, never a row** — writing a default row on a merchant's behalf makes
  "never configured" indistinguishable from "configured to exactly the default," so changing the
  default later would silently skip everyone.

### Final working & architecture
The Ledger asks Settlement for the holding period through a narrow `HoldingPeriodPolicy` port (an arrow
pointing *out* of the Ledger, carrying only a duration — nothing lets an outside caller move money; the
release job still posts its own journal from inside the Ledger, in response to time passing).

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** capture → funds sit in `MERCHANT_PENDING` → holding period elapses → release job
  posts pending→available → balance is now settleable.
- **Edge — partial refund before release:** the per-payment net (not gross) is what's released, so
  available is correct.
- **Edge — refund *after* release:** comes out of `available` (nothing of that payment is pending
  anymore), which can drive available negative — correct, the merchant owes the difference.
- **Known limit (honestly named, and it sets up Post 26):** release and a refund reversal of the same
  payment can **interleave**, and the loser is `available` — under READ COMMITTED, neither takes a
  lock, so a reversal can commit its pending debit *after* the job read the pending remainder, and the
  job releases the gross. The total owed stays right (pending goes negative by exactly what available
  is too high by), but *available is the figure that gets paid out*. The window is one short
  transaction against an hourly job. The fix (a row lock, like the Post 14 refund race) is deliberately
  deferred to Post 26 — *"a lock whose only proof is an argument, with no test that fails when it's
  removed, is false coverage this repo deletes on sight."*

### Image direction
**Concept:** two buckets and a clock — money maturing from pending into available.

Two labeled tanks: `MERCHANT_PENDING` and `MERCHANT_AVAILABLE`, with a valve between them gated by a
clock (the holding period). A ledger journal line is shown *being the valve* (debit pending, credit
available), not a status flag. Show a small "may go negative" tag on available.

**Prompt:**
> A dark-mode conceptual finance diagram, 16:9. Two vertical tank/reservoir shapes labeled
> "MERCHANT_PENDING" (left, partially full) and "MERCHANT_AVAILABLE" (right). Between them a pipe with
> a valve, and the valve is gated by a small clock icon labeled "holding period". Flowing through the
> valve is a ledger journal line reading "debit PENDING / credit AVAILABLE". A small tag on the
> AVAILABLE tank reads "can go negative (merchant owes)". Monospaced labels, near-black background,
> teal accent, thin clean lines, professional, no photorealism.

---

## POST 26 — Money leaves the platform

**Suggested title:** `PayMesh Devlog #26: A "200 OK" From the Bank Doesn't Mean the Money Moved`

### What we built
Settlement: a scheduled job cuts a merchant's available balance into a batch, submits a payout to the
provider, and credits the cash account `BANK_CASH` **only when the provider's signed callback confirms
the money landed.** A terminal failure returns the funds to available via a new reversal journal. This
is the first capability where money *leaves* the platform.

### The problem we're solving
Merchants need to actually get paid. That means the first capability that credits a real cash account
and the first that hands an instruction to a provider expecting real money to move — both new shapes of
risk, both governed by the same invariant everything on the money path is: a committed movement must
never be lost, silently duplicated, or become unauditable.

### The problem we faced while developing
The core temptation is to treat the provider's acceptance as settlement. **A 2xx from the provider on
submission means "accepted for processing," not "settled."** Treating it as settlement would post cash
for money a later callback might say never moved. And Post 25 left a known interleave race open on
principle — Settlement is exactly the change that makes a wrong `available` figure get **paid out**, so
the lock can no longer be deferred.

### The solution
- **The provider's word posts the cash, and nothing else.** `BANK_CASH` is credited from the signed
  payout callback, never from PayMesh's own submission — the same rule Payment follows for a capture
  (submitting isn't confirming). Money moves `available → SETTLEMENT_IN_TRANSIT` on cut, then
  `in-transit → BANK_CASH` on the paid callback, or `in-transit → available` on failure.
- **Three journals, and a failure is a *new* one.** `payout.returned` is a new transaction, never an
  edit of the cut — the failed batch and the returned funds are both on the record, and the money is
  settleable again. (The ledger's founding rule — corrections are reversals, not deletes — holding on
  the way *out* just as Refund holds it on the way in.)
- **The Post 25 interleave race is closed here, with a lock *and* a test.** `lockPaymentJournals` takes
  a row lock on a payment's capture journal before reading its available position, in **both** the
  release job and the refund reversal — the two writers that can disagree about a payment. It's the
  Post 14 over-refund lock applied a second time, for the same measured reason (a deferred trigger
  can't fix it — its query runs on the snapshot of the statement that queued it, and both writers
  pass).

### The trade-offs we weighed
- **The cut nets each payment against every prior batch**, so a payment can't be settled twice across
  two batches. That the item sum equals the available balance isn't asserted in the application — a
  **deferred trigger** makes the batch's ledger debit equal the sum of its items, and the two agree
  because both derive from the same entries.
- **No FX, no fee deduction** — a batch is one currency and nets nothing out, because there's no fee
  schedule to split against.
- **A payout is confirmed all-or-nothing** — the simulator answers `SUCCEEDED` or `FAILED` for the
  whole payout; partial settlement isn't modeled because the ledger movement it would need has no
  provider event to trace to.
- **The lost-payout-callback path relies on resubmission** — a payout whose callback never arrives
  stays non-terminal and is resubmitted when due (the provider dedups on the external reference). There
  is no settlement reconciliation reading the provider's daily record the way payments have (Post 20) —
  that's a later capability, and until it exists a permanently lost callback is a *stuck* payout, not a
  lost one.

### Final working & architecture
Settlement commits its own rows and an outbox event in one transaction; the Ledger consumes the event
and posts the journal — **no internal port writes to the ledger** (the same rule since the ledger was
built), so every settlement journal traces to a committed Settlement row and a redelivered event is a
no-op on the journal's idempotency key. Immutability triggers freeze posted batch/item/payout rows;
`AccountType` gains `SETTLEMENT_IN_TRANSIT` and `BANK_CASH`, and — the trap Post 25 named — **both**
account-type CHECKs learn about them.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** job cuts a batch (available → in-transit) → submits payout → provider callback
  `paid` → in-transit → `BANK_CASH`. Money has left the platform, on the record.
- **Failure path:** callback `returned` → in-transit → available via a new reversal journal; batch
  marked `RETURNED`; funds settleable again.
- **Edge — double-settle prevention:** each item is netted against every earlier non-returned batch; a
  deferred trigger ties the batch debit to its item sum.
- **Edge — no destination / below minimum:** no payout destination → merchant isn't payable, no batch
  is cut (cutting one would strand money in a transit account with nowhere to go); below the configured
  minimum → cutting costs more than it moves.
- **Edge — idempotent submission:** a resubmitted payout (the safe response to a stuck one) dedups on
  `uq_provider_payouts_external_reference`, so it never pays twice; each payout is submitted under a
  row lock in its own transaction, so one malformed payout costs one payout, not the batch.
- **`inSettlementMinor` joins the balance** so a merchant watching a payout doesn't see the amount
  vanish from every figure between cut and confirmation.

### Image direction
**Concept:** three accounts and a bank whose *callback* — not its 200 — posts the cash.

A horizontal flow: `MERCHANT_AVAILABLE` → (cut) → `SETTLEMENT_IN_TRANSIT` → (bank callback: PAID) →
`BANK_CASH`. Show the provider returning a fast "202 accepted" that is explicitly **not** what posts
cash (greyed), and a later **signed callback** that is (highlighted). A branch shows FAILED looping
in-transit back to available.

**Prompt:**
> A dark-mode conceptual finance flow diagram, 16:9. Three account nodes left to right:
> "MERCHANT_AVAILABLE", "SETTLEMENT_IN_TRANSIT", "BANK_CASH", connected by arrows labeled "cut" and
> "paid". A provider/bank icon sends two messages: a greyed-out one labeled "202 accepted (does NOT
> post cash)" and a highlighted teal one labeled "signed callback: PAID (posts cash)" — only the
> second arrow reaches BANK_CASH. A red return arrow labeled "FAILED / returned" loops from
> SETTLEMENT_IN_TRANSIT back to MERCHANT_AVAILABLE. Monospaced labels, near-black background, teal +
> one red accent, thin clean lines, professional, no photorealism.

---

## POST 27 — Telling the merchant must never undo the payment

**Suggested title:** `PayMesh Devlog #27: A Notification Failing Can Never Roll Back a Payment`

### What we built
Notifications: a merchant gets a record when something happens on their account (payment
succeeded/failed, refund processed), rendered from a template and dispatched by a (simulated) sender on
a timer. The smallest capability in Phase 2 — and a clean illustration of one rule.

### The problem we're solving
Merchants should be told about account events. But *sending* is I/O to the outside world, and I/O
fails. The design question is the invariant: **a notification failing must never roll back the payment
that triggered it.**

### The problem we faced while developing
This one was mostly a "resist building too much" problem — the plan called for three tables (templates,
delivery attempts, notifications) plus channels and recipients and backoff, most of which model
machinery that doesn't exist yet. The real design work was choosing what *not* to build without cutting
anything load-bearing.

### The solution
- **Record on the event, send on a timer.** The event handler runs inside the dispatcher's transaction
  and only ever writes **one `PENDING` row**. A separate scheduled dispatcher claims PENDING rows with
  `SKIP LOCKED`, hands each to the sender, and marks it `SENT`. Sending is kept out of the event
  transaction for exactly the webhook reason: a send is I/O, and it must never be able to roll back the
  payment. `source_event_id` is unique, which makes the handler idempotent — a redelivered event finds
  the row and does nothing.
- **The sender is a seam** — one interface, one `SimulatedNotificationSender` that logs and succeeds.
  A real email/SMS provider (or a failure profile) plugs in without touching the dispatcher.

### The trade-offs we weighed
- **Two of the three planned tables are *not* built, each with direct precedent:** no
  `notification_templates` (a template is static per-event content that changes with a deploy — it's
  code, like Risk's rules; add the table only when a non-engineer needs to edit copy without a deploy),
  and no `delivery_attempts` (counters on the row answer what a support engineer asks — the same "log
  wearing a table's clothes" call webhooks made).
- **No backoff / `next_attempt_at`** — a simulated sender doesn't fail, so it doesn't need to wait;
  failed attempts (from a real sender) retry on the next ordinary pass until the budget, then `FAILED`.
- **No channel or recipient column** — every notification today is the same channel to the same party;
  a `channel` column with one value is scaffolding for a second channel that doesn't exist.
- **Honest consequence:** because the simulated sender always succeeds, `FAILED` and
  `attempt_count > 0` are only reachable in production once a sender that *can* fail is installed — the
  retry path is proven in tests by injecting a throwing sender, the same way the provider simulator
  reaches failure states a happy sender never would.

### Final working & architecture
Notification is a **leaf**: it imports nothing from any capability and reads events as a
`Map<String,Object>`, exactly like the Ledger and Webhook — so the module-boundary test keeps an empty
allowlist and it's extractable into its own service without touching a producer. One platform-admin
read endpoint for support diagnosis; no merchant-facing list, because nothing asks for one yet.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** `payment.succeeded` event → handler writes one PENDING notification (idempotent on
  `source_event_id`) → timer claims it with `SKIP LOCKED` → simulated sender succeeds → `SENT`.
- **Edge — redelivery:** a duplicate event finds the existing row and does nothing.
- **Edge — send failure (real sender only):** retried on the next pass until the attempt budget, then
  `FAILED`; the payment that triggered it is never affected.
- **The whole point, as the reflection line:** *"The notification is the least important thing in the
  transaction — so it lives outside it. A payment must never depend on whether an email went out."*

### Image direction
**Concept:** a firewall between the money transaction and the outside-world send.

Left: a sealed transaction box (payment + "write PENDING notification" inside it, committing together).
A wall. Right: outside the wall, a timer picking up the PENDING row and handing it to a sender that
*might fail* — with the failure visibly unable to cross back over the wall into the payment.

**Prompt:**
> A dark-mode conceptual architecture diagram, 16:9. Left: a sealed rounded box labeled "one
> transaction" containing two items, "payment committed" and "notification: PENDING", shown committing
> together. A solid vertical wall to its right. Right of the wall: a clock/timer icon pulling the
> PENDING row and handing it to a "sender" node with a small red "may fail" tag; a red arrow from the
> sender toward the payment box is blocked by the wall (crossed out). Monospaced labels, near-black
> background, teal accent with one blocked red arrow, thin clean lines, professional, no photorealism.

---

## POST 28 — A report that admits when it's stale

**Suggested title:** `PayMesh Devlog #28: A Dashboard That Lies About Being Up-to-Date Is Worse Than a Slow One`

### What we built
Reporting: merchant-scoped summaries (payments, settlements) and async CSV export, built from domain
events as **one append-only fact table, aggregated on read** — with an honest `asOf` timestamp that
tells the truth about eventual-consistency lag.

### The problem we're solving
Merchants need reports. But a report built from asynchronously-delivered events is *eventually
consistent* — and the requirement that shapes everything is: the UI must show an as-of timestamp or a
delayed-data signal when the projection lags. The design question isn't "how do we build a dashboard,"
it's "what shape serves both reports and the export, stays a leaf, and tells the truth about being
behind."

### The problem we faced while developing
The obvious design — a row per payment, mutated as it succeeds and is later refunded — has to be
correct under **concurrent and out-of-order** delivery, and the failure mode of getting it wrong is a
*silently understated total*, not an error. That's the exact race the refund path takes a row lock to
avoid.

### The solution
- **One append-only fact table, one row per source event, aggregated on read.** `source_event_id` is
  the **primary key**, so a redelivered event is a refused insert rather than a double-counted payment —
  the idempotency guard *is* the natural key, no second column. A row-per-event design never reads
  before it writes, so there's nothing to race on; corrections arrive as further facts, the way the
  ledger corrects itself with reversal transactions rather than edits.
- **`asOf` is the newest fact's `recorded_at`, never the read clock.** Reporting stamps its own clock on
  each fact as it ingests it and every report returns the maximum. Reporting `now()` would claim
  currency the projection doesn't have; reporting the newest fact means **a relay that stopped shows up
  as an `asOf` that stops advancing** — exactly the delayed-data signal required. `asOf` is `null` when
  there are no facts (an honest "nothing projected yet," not a fresh-looking empty report).

### The trade-offs we weighed
- **Everything is per currency, nothing sums across** — a report is the easiest place in a payment
  platform to quietly add USD to EUR, so both summaries return one entry per currency.
- **`order.paid` is deliberately excluded** from the subscribed types — it's Order's restatement of
  `payment.succeeded`, so counting both would double every collection.
- **Exports are async, request-then-generate** — `POST` records a PENDING row and returns a `202`; a
  scheduled generator renders the CSV. Synchronous generation would let a merchant hold a request thread
  for as long as their history is deep.
- **The CSV lives in a `TEXT` column** — there's no object storage in this project, so a content column
  is the honest option: it won't hold a million-row export, but it won't hand a merchant a download URL
  for a bucket that doesn't exist either. An over-cap export is `FAILED` with a reason naming the
  number, not retried forever.
- **No pre-aggregated rollup** — a `GROUP BY` over one merchant's own facts is cheap at this size, and a
  rollup is strictly this-table-plus-a-cache (it needs this table anyway, because the export selects
  rows). Add it the day a report is *measurably* slow.

### Final working & architecture
Two summary endpoints and two export endpoints (the download is content negotiation — one resource,
JSON metadata by default and CSV under `Accept: text/csv`; asking for the CSV before it's rendered is a
`409`, keep polling, not a `404`). Reporting names no other capability's types — it reads the payload as
a `Map<String,Object>` out of the envelope, exactly as a consumer in another process would, which is
what lets it stay a leaf while consuming six capabilities' events. Adding a seventh event type is four
coordinated edits, three of which fail at startup if forgotten.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** events flow in → one fact per event (idempotent on `source_event_id`) → summary is a
  `GROUP BY`, export is a `SELECT` → each response carries an `asOf` = newest fact.
- **Edge — redelivery / out-of-order:** a duplicate is a refused insert; order doesn't matter because
  nothing reads before it writes; corrections are just more facts.
- **Edge — lag is visible:** if the relay stalls, `asOf` stops advancing — the delayed-data signal is
  in the contract, not a debug field.
- **Edge — export too big:** `FAILED` with the row count in the reason, not an infinite retry.
- **Reflection line:** *"An eventually-consistent read model that lies about being current is worse than
  one that admits it — so 'how stale am I' is a field in the response, not a footnote."*

### Image direction
**Concept:** a stopped clock as the honest signal.

A stream of event cards flowing into an append-only stack of "facts," each stamped with a `recorded_at`.
A report panel shows totals and a big `asOf: 14:32:07`. Then show the relay stalled (a broken pipe), and
the `asOf` **frozen** at the last fact's time while wall-clock time moves on — the freeze *is* the
delayed-data signal.

**Prompt:**
> A dark-mode conceptual data diagram, 16:9. Left: a stream of small event cards flowing into a vertical
> append-only stack labeled "report_facts", each card stamped with a "recorded_at" time. Right: a report
> panel showing per-currency totals and a prominent line "asOf: 14:32:07". A small inset shows a broken/
> disconnected pipe labeled "relay stalled" and the report's asOf frozen (greyed clock) while a
> wall-clock beside it reads a later time — the frozen asOf highlighted as "the honest staleness signal".
> Monospaced labels, near-black background, teal accent, thin clean lines, professional, no photorealism.

---

## POST 29 — A log that won't let the action happen if it can't record it

**Suggested title:** `PayMesh Devlog #29: For a Security Log, Failing to Write Is a Reason Not to Act`

### What we built
An append-only audit log for privileged actions — merchant freezes, role grants, webhook secret
rotations, system recoveries — recorded **in-process, inside the same transaction as the action it
describes**, immutable by database trigger. The last capability of Phase 2, on purpose: its subjects
are what the earlier PRs created.

### The problem we're solving
Phase 2 built plenty of things worth being able to answer "who did this, when, and why" about, and
nothing recorded them in one place. `security_events` covers authentication; the ledger covers money;
each capability's own history table covers its own transitions in its own tenant scope. There was no
single, cross-capability, tamper-evident, compliance-facing record of privileged operations.

### The problem we faced while developing
Notification and Reporting are pure *event consumers* — everything they record already rides the
outbox. **Audit's subjects don't.** Risk publishes no event; webhook secret rotation publishes nothing;
merchant and user freezes publish nothing. (This was verified in code before choosing the shape.) So
the event-consumer pattern that worked for the last two capabilities structurally can't work here.

### The solution
- **Record in-process, inside the acting transaction** — an `AuditRecorder` port a privileged service
  calls from *inside the same transaction* that commits the action. The audit row and the action commit
  together: a committed suspension always carries its audit event; a rolled-back one leaves no false
  trail. This is the "never unauditable" half of the governing invariant.
- **The corollary, stated because it's real: a failure to record is a failure to act.** If the audit
  append throws, the privileged action rolls back with it. For a security log that's *correct* — an
  action that cannot be audited must not silently happen.
- **Append-only, enforced by a trigger** (a `BEFORE UPDATE OR DELETE` that RAISEs), copied from the
  ledger's — a trigger rather than a `REVOKE` because a revoke says nothing about the migration owner
  and is absent under Testcontainers (which connects as superuser). A correction to a mistaken audit row
  is a *new* row, never an edit.

### The trade-offs we weighed
- **Operational/security history here; financial journals stay in the ledger.** Merging them would put
  an actor beside a debit and force a compliance reviewer's "list every privileged action" through a
  double-entry query. The audit log deliberately *overlaps* a few history tables — those are each one
  capability's record of its own transitions; the audit log is the single cross-capability place, the
  same shape as Reporting restating ledger figures.
- **Before/after values and caller IP are stored as SHA-256 hashes, never plaintext** — the log proves
  *that* state changed and *who* changed it, never what a rotated secret was. The recorder is the one
  place plaintext becomes a hash, so a caller can't store a secret by forgetting to. Honest ceiling:
  unsalted SHA-256 is guessable for low-entropy before/after (`ACTIVE`→`SUSPENDED`) and brute-forceable
  for an IPv4 — acceptable now because no wired caller passes a real IP and those fields hold no secret;
  upgrade path is HMAC under a pepper, marked in code.
- **The coupling is real and stated:** because recording is synchronous and transactional, a broken
  recorder fails the privileged action — audit's uptime is now part of every privileged action's
  uptime. A capability that must *never* be blocked by audit would need the event-consumer shape, which
  its subjects can't provide.

### Final working & architecture
The port lives in `shared`, so Merchant, Identity, and Webhook depend on it exactly as they depend on
`Clock`; the single implementation is in `audit.infrastructure`, with every module-boundary arrow
pointing *at* Audit and none pointing out. Wired now: merchant freeze/activate/close, all the user
privileged-access actions, and webhook secret rotation. Deferred (port ready): risk decisions (Payment's
`confirm` isn't wired yet — wiring audit now would record something that never happens), payout/recon
recoveries (SYSTEM-actor), and the startup bootstrap. Platform-admin read surface + async CSV export,
copied from Reporting.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** an operator suspends a merchant → the suspension and its audit row (hashed
  before/after, actor, hashed IP) commit in one transaction → the log shows who/when/why.
- **Edge — the coupling fires:** if the audit append throws, the suspension rolls back with it —
  intended for a security log.
- **Edge — immutability:** a raw `UPDATE`/`DELETE` against `audit_events` is refused by the trigger with
  the application entirely out of the path (proven by an integration test issuing raw SQL).
- **Edge — forgetting to audit a new action:** invisible to that capability's own tests, but caught by
  an end-to-end recording test where one is written.
- **Reflection line:** *"Most systems treat the audit log as a side effect. For privileged actions, I
  inverted it: if I can't write the record, the action doesn't get to happen."*

### Image direction
**Concept:** the action and its audit row welded into one atomic commit — with a chain link that breaks
the *action* if the *log* breaks.

Show one transaction box containing two linked items: "suspend merchant" and "audit_events row (hashed
before/after, actor, IP)". A chain link binds them. Then show the audit half failing (red) and the chain
*pulling the action back* — both rolled back together. A small immutable-lock icon on the audit row.

**Prompt:**
> A dark-mode conceptual security diagram, 16:9. Center: a single rounded transaction box containing two
> items bound by a chain link — "action: SUSPEND merchant" and "audit_events: {who, when, hashed
> before/after, hashed IP}" — with a small padlock icon labeled "append-only (trigger)" on the audit
> item. To the right, an alternate state: the audit item glows red ("record failed") and the chain drags
> BOTH items into a greyed "rolled back together" state. Monospaced labels, near-black background, teal
> accent with one red failure state, thin clean lines, professional, no photorealism.

---

# ==================================================================
# SEASON 3 — PHASE 3: THE MICROSERVICES EXTRACTION (Posts 30–36)
# ==================================================================

_Framing note for ChatGPT: this is a new chapter. For 29 posts PayMesh was one program. These posts are
about carefully tearing it into nine services **without losing or duplicating a cent** — leaves first,
the money path last, every step reversible. The recurring theme: "in one process the compiler is your
schema; across a wire, nothing is."_

## POST 30 — Losing the compiler as your schema

**Suggested title:** `PayMesh Devlog #30: In One Process, the Compiler Is Your Schema. Across a Wire, There Isn't One.`

### What we built
The Kafka backbone: a broker in the stack, a versioned wire contract (`EventEnvelope`), and a publisher
that puts the existing outbox row on a topic — **with no caller yet.** The transport and the contract,
deliberately dormant, so the next PR can flip it on.

### The problem we're solving
Phase 3 turns the monolith into nine services, and the plan's first rule forbids the shortcut: **no
distributed transaction, ever.** A service commits its own database and its own outbox row, and
everything beyond that is eventual. The *producing* half already existed (every state change has
committed an outbox row in its transaction since early on). What's missing is the transport — and, less
obviously, **a contract for what goes on it.** In one process the envelope is a Java record both sides
compile against; the compiler is the schema. Across a wire there's no compiler, and the first service
that renames a field breaks eight others at runtime.

### The problem we faced while developing
The topic-naming rule looked trivial and wasn't. The first cut named topics after the event-type's
domain prefix (`payment-events`, `settlement-events`) — and **Settlement is the counterexample**: one
`SETTLEMENT_BATCH` aggregate emits `settlement.batch_cut` *and* `payout.paid`/`payout.returned`, so the
prefix rule scattered one aggregate's stream across two topics. A partition key only orders *within* a
topic — across two, nothing is ordered — and the Ledger consumes all three, causally chained
(`batch_cut` moves funds to in-transit; `payout.paid` discharges in-transit). Reordered, the Ledger is
asked to discharge funds it hasn't yet moved there.

### The solution
- **Topic per aggregate, named from the aggregate *type*, not the event prefix.** `ORDER` →
  `order-events`. Deriving the topic from the same field the partition key comes from makes **one
  aggregate, one topic, one key space**, so per-aggregate ordering is a property of the naming rule
  rather than a coincidence about which event types share a prefix. Per aggregate (not per service)
  because this phase moves capabilities between deployables repeatedly and an aggregate doesn't move.
- **The envelope is the outbox row, formalized — but a separate type.** `EventEnvelope` carries exactly
  what the outbox has always carried, but as *plain strings*, because its field names are parsed by
  services we don't deploy — whereas the internal `OutboxEvent` holds validated value objects and its
  names are ours to rename freely. The payload stays an open `Map`, so a consumer never imports the
  producer's domain types.
- **The publish *blocks* on the broker's acknowledgement.** `KafkaTemplate.send` returns as soon as the
  record is in the local buffer, before any broker has it — so the publisher waits for the ack and
  throws if it doesn't come. A relay that treated the buffered return as success would stamp
  `published_at` on an event a crash then discards: committed to the database, lost on the way out.

### The trade-offs we weighed
- **Kafka (KRaft), not RabbitMQ or Postgres-as-a-queue**, for one reason that matters here: retained,
  replayable, partitioned log semantics. A new service — and this phase creates eight — can be pointed
  at a topic and consume history it wasn't running for. A queue throws that away on acknowledgement.
- **`acks=all` + idempotent producer**, set explicitly — but neither is exactly-once and neither
  replaces the consumer inbox (the earlier refusal of exactly-once stands). They only stop the producer
  being a *second, avoidable* source of duplicates on top of the unavoidable one.
- **`auto-offset-reset=earliest`, not Kafka's default `latest`** — a new consumer group must start at
  the beginning of the topic. Starting at "now" silently skips every event published before the group
  existed, which on this system means silently skipping committed money movement. Replay duplicates are
  safe (the inbox); a gap is not.
- **Nothing is used yet** — the relay still dispatches in-process; the publisher is a wired bean with no
  caller. *A flag with nothing behind it is a flag that's never been on.* No schema registry, no
  Avro/Protobuf (JSON via the app's own mapper — a registry polices a contract with one producer today).

### Final working & architecture
The app still starts and the whole suite still passes **with no broker anywhere** — a `KafkaTemplate`
opens no connection until something sends, and nothing sends. That's the rollback: it's the current
state. Only one test starts a broker (Testcontainers, the same image `docker-compose` runs). The flagged
concern handed to Post 31: from the moment something publishes, a broker outage means events pile up in
the outbox — and the existing 25-attempt dead-letter budget would treat a simple outage as poison.

### Happy-flow metrics, exceptions & edge cases
- **Happy path (this PR):** app boots, suite passes, no broker required; the publisher round-trips an
  envelope in the one Testcontainers test.
- **Edge — unknown fields:** a consumer ignores payload keys it doesn't recognize (open map,
  `FAIL_ON_UNKNOWN_PROPERTIES` off) — asserted by a test, not assumed, so a producer ships an additive
  change with no coordinated deploy.
- **Edge — versioning:** additive within a version; anything else is a new version, and a consumer that
  doesn't recognize a version **throws** (redelivery applies, the event waits) rather than guessing.
- **Edge — ordering:** guaranteed per aggregate (one topic, keyed by aggregate id); nothing may depend
  on cross-key ordering, now enforced by the transport.
- **Reflection line:** *"Half of going distributed is choosing what's ordered with respect to what. Get
  the topic name wrong and you've silently un-ordered the money path."*

### Image direction
**Concept:** one aggregate's causally-chained events, correctly on one topic vs. wrongly split across
two.

Top (wrong, red): `SETTLEMENT_BATCH` emitting `batch_cut` and `payout.paid` into TWO topics, with a
tangled/reordered arrow reaching the Ledger out of order. Bottom (right, teal): both events on ONE
`settlement-batch-events` topic, keyed by the same `stl_` id, arriving in order. Emphasize "one
aggregate, one topic, one key space."

**Prompt:**
> A dark-mode conceptual event-streaming diagram, 16:9, split top/bottom. TOP (marked wrong, red
> accents): an aggregate node "SETTLEMENT_BATCH" emitting two event cards "batch_cut" and "payout.paid"
> into two separate topic cylinders, with crossed/tangled arrows reaching a "Ledger" consumer labeled
> "out of order". BOTTOM (marked correct, teal accents): the same aggregate emitting both event cards
> into a SINGLE topic cylinder labeled "settlement-batch-events (key = stl_...)", arrows arriving at the
> Ledger neatly in sequence. Monospaced labels, near-black background, red vs teal contrast, thin clean
> lines, professional, no photorealism.

---

## POST 31 — The safety net that nearly stalled the money path

**Suggested title:** `PayMesh Devlog #31: My Safety Net for Going Distributed Almost Broke the Thing It Protected`

### What we built
The dual-path relay: the outbox now delivers **both** in-process (as before) *and* to Kafka, and a
Kafka listener feeds the same handlers through the same inbox — so every event is delivered twice and
applied once, and either path alone is sufficient. That redundancy is the point: it's the safety net
that makes every later extraction reversible by a **flag** rather than a redeploy.

### The problem we're solving
Post 30 built the transport with no caller. This PR gives the publisher a caller and the topics a
consumer — *without removing the in-process path* — so the system can be pulled apart one service at a
time with a live fallback the whole way.

### The problem we faced while developing
The first cut had a real money-path bug, and it's the sharp lesson of the PR. It gated **one**
`published_at` column on **both** sinks: dispatch in-process, then publish to Kafka, then stamp. When
the broker is down, the Kafka failure leaves the row unstamped so it retries — but the row was *already
delivered in-process*, and it now sits at the head of the oldest-first, **bounded** in-process claim
batch. During a sustained outage the batch **saturates** with in-process-done, Kafka-pending rows, and
newly committed events are never claimed at all. **A Kafka outage stalls in-process delivery** — the
Ledger stops posting captured payments while the broker is unreachable. The invariant the whole phase
exists to protect, broken by the safety net meant to protect it.

### The solution
- **Two independent columns, two independent claim passes** — `published_at` (in-process, unchanged) and
  `kafka_published_at` (new, V37). An in-process-delivered row leaves the in-process claim the instant
  `published_at` is stamped, regardless of Kafka, so **the money path never waits on the broker.** Worth
  a migration despite the plan's "DB: none," because it's the whole fix.
- **The dead-letter budget governs the in-process sink only; a broker outage is not a poison.** The
  budget exists for one failure mode — an event whose *content* a handler can never apply (a property of
  the event). A broker being unreachable is the opposite: **global** (it fails every event equally) and
  **self-healing** (everything succeeds the moment it returns). So the Kafka pass has **no budget** —
  a send failure retries indefinitely until the broker accepts it, never dead-lettered — which is safe
  here precisely because the two-column separation means it blocks nothing in-process.
- **One consumer, the existing dispatcher, the existing inbox.** Not one listener per handler — **one**
  `@KafkaListener` on the `.+-events` topic pattern handing each record to the same `EventDispatcher`
  the in-process path uses. The dispatcher *is* the fan-out (routes to every subscribed handler, dedupes
  each through `processed_events`), so a per-handler listener would rebuild that N times.

### The trade-offs we weighed
- **One flag, `delivery.mode` = `in-process` | `both`, default `both`; the `dev` profile forces
  `in-process`** so the ~450 integration tests keep passing with no broker. A `kafka`-only mode is *not*
  built — it arrives with the first extraction that needs it; a mode with no caller is configuration
  debt.
- **Rollback is the flag, and it's a behavioural no-op** — flipping to `in-process` returns the system
  to exactly Post 30's state, because `both` mode changed *nothing* about the in-process path (same
  order, same budget, same stamp). That's what makes it a safe rollback rather than a second failure
  mode.
- **Two money-safe ceilings, marked in code:** the blocking Kafka send runs on the shared relay thread
  (a broker outage can delay the next in-process *tick* — a latency degradation, never a loss or
  reorder; a dedicated thread is the upgrade), and a consumer-side dead-letter topic is deferred to when
  a capability is Kafka-*only* (in `both` mode the in-process consumer, with its inbox and budget,
  remains authoritative).

### Final working & architecture
In `both` mode every event is delivered twice and applied once — the extra work is a second inbox-claim
per (handler, event) that reads "already processed" and does nothing, the measured cost of running the
safety net during the transition. The two passes report separately, and a second age on the health
indicator (`oldestUnpublishedToKafka`) makes a broker backlog *visible* without flipping the endpoint to
DOWN (nothing depends on the Kafka stream yet).

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** an event is dispatched in-process (stamps `published_at`) and published to Kafka
  (stamps `kafka_published_at`); a Kafka listener re-delivers it and the inbox dedupes it — applied
  once.
- **Edge — broker down:** in-process delivery is completely unaffected (separate column/claim); the
  Kafka backlog ages visibly and retries forever until the broker returns; no dead-lettering, no
  money-path stall.
- **Edge — poison event:** still caught by the in-process budget on `published_at`, exactly as before.
- **Edge — rollback:** flip to `in-process` → behavioural no-op, back to the pre-Kafka state.
- **Reflection line:** *"The bug wasn't in Kafka. It was that I'd made one column mean two things — and
  the two things had different failure modes. Give each its own column and the outage stops being able
  to touch the money path."*

### Image direction
**Concept:** one shared gate (broken) vs two independent tracks (fixed), during a broker outage.

Top (red): a single `published_at` gate feeding both an in-process sink and a Kafka sink; the Kafka sink
is down, and the backlog jams the shared bounded queue so in-process delivery stops. Bottom (teal): two
separate columns/tracks — in-process flows freely while the Kafka track backs up harmlessly on its own.

**Prompt:**
> A dark-mode conceptual systems diagram, 16:9, split top/bottom, both showing a "broker DOWN" state.
> TOP (marked wrong, red): one outbox with a single gate labeled "published_at" feeding two sinks
> ("in-process" and "Kafka"); the Kafka sink shows a red X and a bounded queue between them is jammed
> full, freezing the in-process sink too. BOTTOM (marked correct, teal): the same outbox with TWO
> separate gates "published_at" and "kafka_published_at" feeding two independent tracks; the in-process
> track flows freely (teal) while the Kafka track backs up harmlessly on its own (amber, not blocking
> anything). Monospaced labels, near-black background, red vs teal contrast, thin clean lines,
> professional, no photorealism.

---

## POST 32 — Nine walls before splitting one service

**Suggested title:** `PayMesh Devlog #32: Building Nine Databases' Worth of Walls — Inside One Process`

### What we built
Schema-per-service: the 46 tables moved out of one shared schema into **ten schemas** (nine services +
`platform`), each with a fenced database role that provably cannot read another service's tables — while
the app still runs as a **single process.** The last big carve that ships before anything is actually
extracted.

### The problem we're solving
Phase 3B onward lifts a capability's packages into their own deployable, and the recipe assumes the
capability's tables *already live in a named schema it can carry out with it.* So this PR draws that
boundary now — making every later extraction "lift the package and point it at its schema," not "split
the database under load."

### The problem we faced while developing
The plan's prose table of which-table-goes-where **disagreed with the code** in three places, and the
code has to win (a table's schema must match the package that carries it at extraction): `api_credentials`
→ `merchant` (not identity — it's merchant API keys), `provider_callbacks` → `payment` (PayMesh's record
of callbacks *received*, not the simulator's outbound log), `refund_callbacks` → `payment` (Refund's own
route). The plan also listed tables that don't exist. Getting this wrong would strand a table in a schema
its owning service can't carry.

### The solution
- **Move by `ALTER TABLE … SET SCHEMA`, keeping every constraint and trigger intact** — the table and its
  indexes, sequences, constraints, and triggers move as one unit, nothing dropped and recreated. In
  particular the ledger's deferred `debits = credits` trigger and its immutability triggers move intact
  *into the ledger schema* — physically confirming the load-bearing claim that the ledger's invariants
  live wholly inside one schema (which is why it extracts last, and safely).
- **Nine restricted `NOLOGIN` roles prove the fence.** Isolation is a *grant*, not a hope: each `*_svc`
  role gets `USAGE` on its own schema plus DML on its own tables and nothing else, and a test `SET ROLE`s
  to each and asserts a `SELECT` against another service's table is refused. That test *is* the isolation
  guarantee made executable — and the exact connection role each extracted service will adopt verbatim.

### The trade-offs we weighed
- **Keep the pervasive `* → merchants` FK, don't drop it here** (the plan says drop). Dropping it now
  would remove a live money-path integrity guard for a whole PR with nothing replacing it until the
  merchant projection (Post 33). Keeping it costs nothing while the database is one cluster, and lets
  Post 33 drop each FK *in the same step that lands its replacement* — never an unguarded interval. The
  one place this PR consciously trades a plan target ("no cross-schema FK") for the invariant ("no
  unguarded interval on the money path").
- **The app keeps one spanning role for now** — one Hibernate over one datasource spans all ten schemas,
  so it connects as a role that sees them all. Wiring nine datasources now would be nine of everything
  for a process that's still one.
- **Lean carve:** one Flyway history (not nine) and one copy each of the shared platform tables (outbox,
  inbox, idempotency) in a `platform` schema — both deferred to each service's extraction, each marked
  in code. Splitting the platform tables nine ways now would mean teaching the shared relay/dispatcher/
  filter which schema a row belongs to — routing with no caller yet.

### Final working & architecture
Entities keep bare `@Table(name="orders")` and every connection sets a `search_path` across all ten
schemas — unambiguous *because all 46 table names are globally unique* (they shared one schema until
today), so no wrong schema can shadow a table. Still one process, still green, still reversible (rollback
is `SET SCHEMA public` for all 46 and dropping the roles). A new migration from here on must
schema-qualify its tables.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** app boots identically; every capability's tables now live in a schema it can carry out
  of the process.
- **Edge — the fence holds:** `SchemaIsolationTest` proves each `*_svc` role is refused
  (`permission denied for schema …`) on every other service's tables and succeeds on its own.
- **Edge — cross-schema FK still enforced:** the kept `* → merchants` FK works across schemas within one
  database.
- **Edge — dev-role tolerance:** role creation is wrapped so a dev role lacking `CREATEROLE` skips it with
  a notice (roles are extraction-prep), while the schema moves themselves fail loud if they fail.
- **Reflection line:** *"You don't split a database under production load and hope. You draw the walls
  first, in one process, and prove a service literally cannot reach across them — then splitting is a
  lift, not a gamble."*

### Image direction
**Concept:** one big room partitioned into ten labeled, walled rooms — a guard at each door.

Show 46 table icons regrouping from one open floor into ten labeled rooms (`identity`, `merchant`,
`payment`, `ledger`, `settlement`, `risk`, `simulator`, `webhook`, `engagement`, `platform`), each room
with a small keycard/guard at its door labeled `*_svc` and a red "access denied" on a hand reaching into
the wrong room.

**Prompt:**
> A dark-mode conceptual architecture diagram, 16:9. A large floor is divided by walls into ten labeled
> rooms: "identity", "merchant", "payment", "ledger", "settlement", "risk", "simulator", "webhook",
> "engagement", "platform", each containing a few small database-table icons. Each room's doorway has a
> small keycard reader labeled with its service role (e.g. "payment_svc"). One hand/arrow reaching from
> one room into another is stopped with a red "permission denied" badge. Monospaced labels, near-black
> background, teal accent with one red denial, thin clean lines, professional, no photorealism.

---

## POST 33 — To stop sharing a table, copy it

**Suggested title:** `PayMesh Devlog #33: Every Service Keeps Its Own Copy of "Which Merchants Exist" — Fed by Events`

### What we built
The merchant reference projection: every service that used to point a foreign key at the `merchants`
table now keeps its **own local `merchant_ref` copy** (merchant id, status, updated-at), fed by
`merchant.*` events through its inbox — and the last boundary-crossing foreign key is dropped in the
same migration that lands the replacement.

### The problem we're solving
A service must own its data and must not read another service's tables. But ~18 tables across seven
schemas still had a foreign key into `merchants`, and the platform's merchant-status gate still *read*
the `merchants` table directly. That's the last direct cross-service coupling — and it has to be replaced
by something before the FK can be dropped, or there's an unguarded interval on the money path.

### The problem we faced while developing
Reading a **projection** instead of the authoritative table introduces lag the direct read never had —
and in the monolith the relay is asynchronous and off under `dev`, so a just-registered or just-activated
merchant is routinely absent from or stale in the projection for a relay cycle. The old gate was always
current; the new one is eventually consistent **even in one process.** A gate that returns a flat 403 for
"I haven't heard about this merchant yet" would break every register-then-immediately-transact flow.

### The solution
- **A `merchant_ref` per consuming schema, not one shared copy.** A single shared `merchant_ref` read by
  every service is "a shared database wearing a lanyard" — it re-couples every consumer to one table and
  leaves the boundary the FK left open still open. Per-schema costs more migration lines now and **zero
  rework at extraction** — each service lifts its schema with its projection already present and warm.
- **Merchant emits lifecycle events in the acting transaction** (`merchant.registered/activated/
  suspended/closed`), via the codebase's universal outbox pattern rather than the plan's "scan the
  history table" — in-transaction emission gives the atomicity the outbox was built for; a separate
  history-scanner would be a second, weaker mechanism for a guarantee we already have.
- **The gate gains a third outcome.** It stops returning a boolean: **present + ACTIVE → allow**;
  **present + not-active → 403** (unchanged); **absent → 503 `MERCHANT_NOT_YET_AVAILABLE`, retryable.** A
  client that registered and immediately transacts retries until the projection catches up — the same
  shape as any at-least-once path.

### The trade-offs we weighed
- **Suspension is now eventually consistent — a real, stated walk-back of an earlier "no cache, ever"
  stance.** The old gate read `merchants` directly and was emphatically *not* cached, precisely so a
  suspended merchant couldn't keep trading. The projection *is* a cache: a suspended merchant keeps
  transacting until `merchant.suspended` propagates — one relay cycle. Accepted because in production the
  relay runs continuously (the window is seconds) and **suspension is a policy action, not a money-
  integrity one** — a few extra seconds of trading doesn't lose or duplicate money, which is the governing
  invariant. If a class of action ever needs a zero window, the answer is a synchronous call to the
  merchant service *for that action*, not un-caching the projection.
- **Accepted ceiling:** a merchant *present but stale* (activated but the event not yet projected) reads
  as a brief 403, not a 503 — status alone can't distinguish "genuinely pending" from "activated-but-
  lagging." One relay cycle, self-heals; a heuristic would be speculative complexity for a sub-second
  window.
- **One projector writes every copy** (schema-qualified `INSERT … ON CONFLICT` in a loop, all copies in
  one transaction so they're identical by construction) — the one place a monolith-era convenience stands
  in for what becomes per-service at extraction. First use of `JdbcTemplate` in the codebase, because
  seven near-identical JPA entities to avoid it would be exactly the boilerplate the projection is small
  enough to make absurd.

### Final working & architecture
V39 drops the 17 `* → merchants` FKs across six consuming schemas but **keeps** the per-row format CHECK
(it never needed the foreign table, only the shape) and the composite tenant FKs that point *within* a
service. For event-fed rows the dropped FK's guarantee is replaced by **provenance** (the merchant id
came from a domain event the producer emitted for an already-validated merchant); for authenticated
writes, the gate on the write path is the replacement (a bogus merchant id can't be in a valid token).
The `platform` schema keeps its FKs — those tables aren't consumers reading merchant status. Everything is
re-attachable for rollback until the merchant service actually leaves.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** merchant registers → `merchant.registered` flows → each consuming schema's projection
  upserts → the gate reads its local copy.
- **Edge — read-your-write lag:** register then immediately transact → **503 retryable** until the
  projection catches up (seconds in prod), not a 500.
- **Edge — stale status:** activated-but-not-yet-projected → brief 403, self-heals in one relay cycle.
- **Edge — no new leak:** only authenticated callers reach the gate and a valid token always names a real
  merchant, so `UNKNOWN` is always propagation lag, never a probe.
- **Test impact (honest):** every test that changes merchant status then asserts the gate must now drive
  the relay between the change and the assertion — the eventual consistency being exercised honestly, not
  scaffolding.
- **Reflection line:** *"'Don't share a database' sounds clean until you ask how nine services agree on
  which merchants exist. The answer isn't a shared table — it's each service keeping its own copy, fed by
  events, and admitting when that copy is a beat behind."*

### Image direction
**Concept:** one authoritative emitter, six local copies fed by events, FK arrows cut.

Center: a `merchant` service emitting `merchant.*` events. Around it: six service boxes each holding a
small `merchant_ref(id, status)` table, each fed by an event arrow. Show the old direct FK arrows into
`merchants` as **cut/severed** (dashed red), replaced by the event arrows (teal). One consumer shows the
gate returning `503 NOT_YET_AVAILABLE`.

**Prompt:**
> A dark-mode conceptual distributed-systems diagram, 16:9. Center: a node labeled "merchant service"
> emitting event cards "merchant.registered / activated / suspended". Radiating outward to six service
> boxes (payment, ledger, settlement, risk, webhook, engagement), each containing a small table card
> "merchant_ref (id, status)", each connected by a teal event arrow. Old direct arrows from those boxes
> into a central "merchants" table are drawn severed/dashed in red. One service box shows a small badge
> "503 MERCHANT_NOT_YET_AVAILABLE (retryable)". Monospaced labels, near-black background, teal with
> severed-red accents, thin clean lines, professional, no photorealism.

---

## POST 34 — One front door that doesn't trust itself

**Suggested title:** `PayMesh Devlog #34: The Gateway Authenticates Every Request — Then Deliberately Doesn't Trust That It Did`

### What we built
The API gateway: a standalone deployable in front of the platform that validates JWTs at the edge, routes
every prefix to the right place, and rate-limits client traffic — stood up now, while everything is still
one process, so that when services split the front door and its contracts already exist.

### The problem we're solving
North-south (client → platform) traffic should go through one entry point that authenticates, routes, and
rate-limits; east-west (service → service) stays direct. Standing the gateway up *before* the services
split means that when a capability leaves, a route just re-points from the monolith to the service — **no
client changes.**

### The problem we faced while developing
The edge auth had to mirror the monolith's public/authenticated split **exactly**, and the monolith does
*not* require a token everywhere. If the edge demanded a JWT on the callback or simulator routes, every
one would 401 at the gateway before the monolith's signature/shared-key filter ever ran — **stranding a
payment already taken.** So the permit list (auth routes, merchant registration, HMAC callbacks, sim
routes pass through unauthenticated) is a faithful copy, and getting it wrong strands money.

### The solution
- **The gateway validates the token; it does *not* authorize the merchant.** It rebuilds the *same* HS256
  decoder from the *same* shared secret the monolith uses — it's not a second authority, it's the same
  check moved to the front. Which merchant a caller may act for stays a per-row decision the monolith
  makes next to the data.
- **Defense in depth, deliberately: the monolith still authenticates every request itself.** A service
  must never trust that something in front of it did the check — the gateway is a filter, not the only
  lock.
- **The edge decoder matches the monolith's *exactly*** — zero clock skew, no empty-`exp` allowed — not
  Nimbus's laxer defaults (60s skew, empty exp), or "the same check moved to the front" wouldn't be true:
  a token a minute stale would pass the edge and be rejected only by the monolith.

### The trade-offs we weighed
- **Rate limiting is Redis-backed and *fails open*.** Redis is a throwaway counter store, not an
  authority (the graceful-degradation rule: it may fail without corrupting payments). If it's unreachable
  the limiter forwards the request *unlimited* rather than 500-ing all of `/api` — and the guard is
  narrowed to a Lettuce exception in the cause chain, so a *backend* failure is never mistaken for a
  limiter failure and a non-idempotent write is never re-forwarded. Callback/sim routes are forwarded
  **without** a limit — a provider retrying a delivery it's contractually required to retry must not be
  throttled into stranding money already moved.
- **A standalone module, not a reactor conversion** — one front door's job; a parent aggregator pom earns
  its keep once 3B has several service modules, and doing it now would restructure every build/CI path
  for one module.
- **Three specific, load-bearing pins, recorded as workarounds not style:** the proxy hop is forced to
  HTTP/1.1 (h2c to a plaintext backend gets its request body cancelled mid-stream), Lettuce pinned to
  6.3.2 (the rate-limiter's CAS path predates Lettuce 7), and Spring Cloud's Boot-4.0-only compatibility
  check disabled for Boot 4.1 rather than holding the whole platform back a minor.
- **Accepted ceiling:** the rate-limit key is the raw remote address — correct *while the gateway is the
  edge*; trusting a client-supplied `X-Forwarded-For` here would let anyone spoof their IP and dodge the
  limit. Put a *trusted* load balancer in front and the switch is one property (not set while the gateway
  is directly reachable).

### Final working & architecture
Two route groups: `/api/**` (rate-limited client traffic) and `/internal/**` + `/sim/**` (forwarded
without a limit). Every route points at the one monolith today; in 3B each prefix re-points at its own
service and nothing else in the class changes — that one-line re-point being a route change, not a client
change, is the whole reason the gateway exists before the services do. The shared HS256 secret is the cost
of symmetric tokens; when Identity is extracted and moves to asymmetric keys, the decoder fetches a public
key/JWKS and the secret disappears — one line. The gateway is optional until 3B (rollback: clients address
the monolith directly).

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** an authenticated `/api` call is validated at the edge and forwarded unchanged; the
  monolith authenticates it *again* (defense in depth).
- **Edge — unauthenticated protected route:** refused **at the gateway**, never reaches the backend, with
  the monolith's own 401 shape.
- **Edge — callbacks/sim:** pass through the edge without a bearer token so their HMAC/shared-key check
  can run at the monolith.
- **Edge — Redis down:** the limiter fails *open* (forwards unlimited), fast (2s timeout, reject-on-
  disconnect), and never re-forwards a non-idempotent write.
- **Edge — burst:** past capacity → `429` rewritten into the house `{code:"RATE_LIMITED"}` shape so a
  throttled client parses the same body as a 401.
- **Reflection line:** *"The gateway checks your token at the door. Then every service behind it checks it
  again — because 'something in front of me already did it' is exactly the assumption an attacker wants you
  to make."*

### Image direction
**Concept:** a front door with a guard, and every inner room *also* has a lock.

A building: the gateway is the front door with a guard checking a JWT (and a turnstile = rate limit). Past
it, the monolith's rooms each *also* have their own lock re-checking the same token. Show callback/sim
doors as side entrances that bypass the front guard (to be checked by their own HMAC lock inside).

**Prompt:**
> A dark-mode conceptual security-architecture diagram, 16:9. Left: a building's front door labeled
> "API gateway" with a guard checking a "JWT" and a turnstile labeled "rate limit (fails open)". Behind
> the door, several inner rooms (payment, order, ledger...) each drawn with their OWN small padlock
> labeled "re-validates JWT". A separate side entrance labeled "/internal, /sim" bypasses the front guard
> and leads to a room with its own lock labeled "HMAC / shared key". Monospaced labels, near-black
> background, teal accent, thin clean lines, professional, no photorealism.

---

## POST 35 — The first service to leave (on purpose, the one that didn't matter)

**Suggested title:** `PayMesh Devlog #35: I Extracted the Service That Mattered Least First — Deliberately`

### What we built
The first actual extraction: the provider simulator moved out of the monolith into its own deployable
(`provider-sim/`, its own port, its own schema, its own build) — the **pilot**, chosen precisely because
nothing financial is at risk in getting it wrong.

### The problem we're solving
All of Phase 3A built scaffolding while the monolith stayed one process. This is the first PR that
actually *moves code out* — and the point is to prove the extraction recipe on the safest possible
capability before repeating it on services that carry real event traffic.

### The problem we faced while developing
Two tests couldn't survive verbatim: they imported the simulator's package to drive a real cross-boundary
scenario **in one JVM**, and once the two sides are two processes with two classpaths, that's exactly the
plan's stated risk ("the Java suites can't see cross-service HTTP-surface regressions") arriving one PR
early. The work was adapting them honestly (WireMock standing in for whichever side moved out of reach)
rather than deleting the coverage.

### The solution
- **The move is literal: the package doesn't change, only its process does.** `com.paymesh.simulator`
  relocates unchanged — same classes, same package name, same tests where they could move. This is
  possible *only* because the simulator was built (long ago) with **zero references to PayMesh in either
  direction** — no PayMesh table, no other capability importing it, its own shared key on its surface. A
  module with even one shared type would have made this a rewrite. (Exactly one shared class — an error
  response — was copied and renamed into its own package.)
- **It adopts its existing schema, doesn't recreate it.** The five simulator tables already live in the
  `simulator` schema (moved there in Post 32); the new module's Flyway history uses `baseline-on-migrate`
  to **adopt** them at V1 rather than re-run `CREATE TABLE` against a schema that already has them — while
  a genuinely fresh database still bootstraps identically. It connects as `simulator_svc`, the fenced role
  minted for exactly this day.

### The trade-offs we weighed
- **The wire protocol deliberately does *not* change** — the plan's prose said outbound callbacks would
  "publish to Kafka"; they stay HTTP-POST-signed-body, and that's a *stated correction, not a silent
  rewrite.* The simulator predates the Kafka backbone by nineteen ADRs and was never wired to it; moving
  it to Kafka would bundle a second, unrelated redesign of the callback ordering/dedup contract into the
  one PR whose job is to prove the *move* is cheap. The URLs it already POSTed to just change one number
  (8080 → 8082) — they were real HTTP calls all along, "loopback by coincidence, not by construction."
- **Rollback is no longer a flag** — it's re-pointing the gateway's `provider-sim-uri` back at a monolith
  still carrying the old code (preserved in git). A revert, not a live side-by-side — acceptable because
  the simulator was never on the dual-path relay's critical path (it never spoke Kafka).

### Final working & architecture
A genuinely separate deployable: own pom, own port (8082), own schema, own Flyway history, own secret
guard (one secret, not the monolith's five). The gateway splits one route group so `/sim/v1/**` points at
the new service; nothing else in the gateway changes (those routes were already unauthenticated at the
edge — the receiver has no bearer token to evaluate). The reconciliation job and payout submitter, which
already addressed the simulator over HTTP, change one port number each. The module-boundary tests that
guarded "the simulator imports nothing / nothing imports it" are deleted rather than left to pass
vacuously — that claim is exactly what this PR cashes in.

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** provider-sim boots on 8082 against the `simulator` schema as `simulator_svc`; the
  gateway routes `/sim/v1/**` to it; callbacks POST to the monolith across a real process boundary; the
  suite is green.
- **Edge — Flyway adoption:** against the shared dev DB, Flyway adopts the pre-existing tables at V1; on a
  fresh DB it creates them — same DDL either way.
- **Edge — grants:** two manual grants (`LOGIN`, and `CREATE ON SCHEMA` so Flyway can make its own history
  table) are what the fenced role needed beyond Post 32's `USAGE`.
- **Edge — moved tests:** the callback-delivery test moved to provider-sim with a WireMock receiver; the
  reconciliation test stayed in the monolith with a hand-built JSON document served by WireMock — the same
  "restate the wire contract, don't share it" pattern the simulator always used.
- **Reflection line:** *"The cheapest way to de-risk a scary migration is to run it first on the one
  service where a mistake costs nothing. The pilot's whole job was to prove the recipe — not to also
  relitigate the transport."*

### Image direction
**Concept:** one box lifting cleanly out of a monolith because it was never wired in.

Show a large "monolith" block with many interlocked modules, and the "provider simulator" module lifting
straight out with **no torn connections** — because its only links (HTTP callbacks) are external cables
that simply stretch across a new gap. Contrast with neighboring modules that are visibly interlocked.

**Prompt:**
> A dark-mode conceptual architecture diagram, 16:9. Left: a large block labeled "monolith" made of many
> interlocking module tiles. One tile labeled "provider simulator" is lifted up and out cleanly, floating
> to the right as a separate small box labeled "provider-sim :8082", connected back only by two thin
> external cables labeled "HTTP signed callback" and "HTTP reconciliation" that simply stretch across the
> gap. Neighboring tiles are shown with visibly interlocked edges to contrast how cleanly this one
> detaches. Monospaced labels, near-black background, teal accent, thin clean lines, professional, no
> photorealism.

---

## POST 36 — The first real leaf leaves the process

**Suggested title:** `PayMesh Devlog #36: Now a Merchant's Endpoint Being Down Can't Touch a Payment — Across Processes`

### What we built
The webhook service extracted into its own deployable (`webhook/`, its own port) — the first **merchant-
facing** capability to leave the monolith, and the first extraction where the recipe meets everything the
pilot deliberately avoided: real Kafka-fed event traffic, an authenticated merchant API, and a privileged
action that needs auditing. This is where `main` sits today.

### The problem we're solving
The simulator (Post 35) was the easy case — zero PayMesh coupling. Webhook is isolated in its *own* code
(it imports only its package + shared), but it leans hard on the **platform**: security, tenancy, the
outbox/inbox, idempotency. That platform is what this PR has to carry across a process boundary for the
first time — and it's the template for extracting every remaining leaf.

### The problem we faced while developing
Three things the pilot never had to solve, all at once: (1) webhook is **Kafka-fed** from real domain
events, so it's the first service to actually cash in the dual-path relay's promise rather than merely be
compatible with it; (2) it serves an **authenticated merchant API**, so it needs a JWT boundary and the
merchant-status gate, not a single shared key; (3) it records a privileged action — secret rotation —
through the in-process `AuditRecorder`, but **`audit_events` left this service's reach** when it went to
the `engagement` schema, so that in-process call can no longer survive.

### The solution
- **The capability moves verbatim; the shared platform is *copied*, not shared.** Webhook depends on ~30
  shared classes; renaming them all (as the pilot did for its one class) would rewrite every import for no
  behavior change, so this PR copies the needed `com.paymesh.shared.*` subtree **verbatim, package names
  unchanged** — webhook's own imports don't move a character. The duplication is the stated, paid cost
  until `shared/` becomes a real library (a later PR); pulling that forward for one extraction is the
  speculative-infrastructure move this project keeps refusing.
- **Auditing a rotation moves from an in-process call to an outbox event** — the key money-integrity move.
  Three options were weighed: (B) drop the audit until later = a temporary regression of a non-negotiable
  security invariant, rejected; (C) a synchronous audit API call = rejected because the guarantee can't
  hold across a network call that can fail *after* the action commits; **(A), chosen:** webhook emits a
  `webhook.secret_rotated.audited` event to **its own outbox in the same transaction as the rotation**,
  and the monolith's audit capability consumes it and writes `audit_events`. This moves atomicity from
  "same DB transaction as the action" to "same *outbox* transaction as the action" — **strictly stronger**
  than the synchronous call it replaces. It's exactly the mechanism the Audit extraction will generalize,
  pulled forward one action early.
- **JWT-only security at this boundary, mirroring the gateway.** The ApiKey→JWT filter needs
  `api_credentials`, which is in the `merchant` schema and out of webhook's reach until Merchant is
  extracted — so webhook carries the **JWT half only**, validating the same shared secret the gateway
  does. A raw ApiKey presented directly to webhook is out of scope this PR (the gateway fronts it) —
  stated, not hidden.

### The trade-offs we weighed
- **Webhook now carries its own copies of `processed_events` / `idempotency_records` / `outbox_events`** —
  a Kafka consumer without an inbox isn't idempotent, and the shared `platform` copies are unreachable to
  the fenced role. New physical tables in an already-carved schema (created at the module's V2).
- **Its own Kafka consumer group** (`paymesh-webhook`, not the monolith's) — webhook is now a genuinely
  independent *second* consumer of the event stream, which is the dual-path promise made literal. One
  `@KafkaListener` on the topic pattern feeds the same dispatcher/inbox fan-out; subscribing to a fifth
  event stays one bean.
- **The one gateway route where ordering is load-bearing:** `/api/v1/webhook-endpoints/**` is a *subset*
  of the `/api/**` catch-all (unlike every other route pair), so its bean is pinned with an explicit
  `@Order` and proven by a dedicated routing test rather than left to declaration luck.
- **Accepted, stated costs:** the `shared` subtree is now duplicated between backend and webhook until it
  becomes a library (drift possible, only a boundary test catches it); webhook runs the *full* outbox
  relay for the single event type it produces today — the established pattern's fixed cost, not a bespoke
  one-off.

### Final working & architecture
Four independently-built Maven modules now exist: `backend/`, `gateway/`, `provider-sim/`, `webhook/`. The
invariant is now **literally cross-process**: a merchant endpoint being down, or webhook itself being
down, cannot touch a payment — the payment commits in the monolith and its event waits in Kafka for
webhook to return. Rollback is the gateway re-point plus the dual-path relay meaning no event is lost while
webhook is absent (and the monolith's audit consumer is harmless whether or not webhook is running).

### Happy-flow metrics, exceptions & edge cases
- **Happy path:** a payment succeeds in the monolith → the event flows over Kafka → webhook (own consumer
  group, own inbox) fans out to its handlers → signs and POSTs to the merchant. A merchant registers/
  rotates through the JWT-guarded API, status-gated via the local `merchant_ref` projection.
- **Edge — webhook down:** the payment still commits; its event waits in Kafka; webhook catches up on
  return — the cross-process version of "a notification failing can't roll back a payment."
- **Edge — secret rotation audit:** the rotation and a `webhook.secret_rotated.audited` outbox row commit
  together; the monolith consumes it and writes the byte-identical `audit_events` row it used to write
  in-process.
- **Edge — merchant not yet projected:** register/rotate returns **503 retryable**, not 403 (the Post 33
  third outcome).
- **Edge — raw ApiKey to webhook:** unsupported this PR; the gateway fronts it, and ApiKey exchange
  returns when Identity/Merchant are extracted.
- **Reflection line (and a good season sign-off):** *"For 35 posts, 'a merchant's endpoint being down
  can't affect their payment' was true inside one process. Now it's true across two — and proving that is
  the whole point of tearing the monolith apart carefully instead of all at once."*

### Image direction
**Concept:** the payment and the webhook now on opposite sides of a process gap, joined only by a durable
Kafka log — with the merchant endpoint's failure visibly unable to reach back.

Left process: "monolith" committing a payment and dropping an event onto a Kafka log. A process boundary
(gap). Right process: "webhook :8083" consuming from the log and POSTing to a merchant URL that is **down
(red)** — with the failure clearly stuck on the right side, unable to cross the gap back to the payment.

**Prompt:**
> A dark-mode conceptual distributed-systems diagram, 16:9. Left: a process box labeled "monolith"
> committing a "payment" and placing an event onto a horizontal Kafka log/cylinder in the middle. A clear
> vertical process-boundary gap. Right: a process box labeled "webhook :8083" consuming from the Kafka log
> and sending an arrow to a "merchant endpoint" icon that is DOWN (red, broken). The merchant failure is
> shown contained on the right side, with a red arrow toward the payment blocked by the process gap
> (crossed out). A small note: "event waits in Kafka until webhook returns". Monospaced labels, near-black
> background, teal with contained-red accents, thin clean lines, professional, no photorealism.

---

## Where the project actually is now (context for your posts)

After Post 36 you're caught up to `main`: **Phases 1 and 2 complete; Phase 3A merged (Kafka backbone,
dual-path relay, schema-per-service, merchant_ref projection, gateway); Phase 3B PRs 6–7 merged (Provider
Simulator and Webhook run as their own deployables).** Four independently-built modules, 42 ADRs.

**The next devlog posts (37+), when you build them, are the rest of the extraction**, in this planned
order — same brief structure applies:

1. **Engagement** (Notification + Reporting + Audit) — the read-side consumers leave together; Audit's
   in-process recorder generalizes into the `*.audited` outbox-event mechanism Post 36 pulled forward.
2. **Risk** — the first *synchronous* extraction (confirm needs a risk decision over the network, with a
   fail-open/closed-by-tier policy).
3. **Identity**, then **Merchant** (which closes the loop Post 33 opened — the real owner of merchant
   status becomes the sole emitter every projection consumes).
4. **Settlement**, then the **money path** (Order + Payment + Customer + Refund as one service), then the
   **Ledger last and whole**.
5. **Observability across the hops**, then **decommission the monolith**.

For each, pull the matching `docs/decisions/ADR-0XX-*.md` (or the Phase 3 plan section for not-yet-built
PRs) and follow the same seven-section + image structure this document uses.
