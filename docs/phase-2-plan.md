# PayMesh Phase 2 — Execution Plan

_Written 4 August 2026, at the start of Phase 2. Phase 1 is complete and green:
1176 tests, 22 migrations (V1–V22), 26 ADRs, a Postman collection running 210
requests green._

This is the plan of record for Phase 2. It says what each PR delivers, what it
depends on, and which migration and ADR numbers it owns. Read
`docs/project-status.md` for where the code actually is; read this for where it
is going.

---

## The shape of the problem

Phase 2 is four SDD capabilities — Risk (§14), Settlement (§17), Webhook (§18),
Notification/Reporting/Audit (§19) — and they are not equally entangled.

**Three of them are pure event consumers.** Webhook, Notification and Reporting
each plug in as an `EventHandler` on events that already flow today:
`payment.succeeded`, `payment.failed`, `refund.succeeded`, `order.paid`. None of
them modifies a line of an existing capability. The dispatcher, the
`processed_events` inbox and the retry budget (ADR-016, ADR-025) are already
built and already carry these events to an empty handler list.

**Risk touches exactly one existing call site** — Payment's confirm — and is
otherwise new tables.

**Settlement is the only capability with a real prerequisite.** It is blocked on
the Ledger, and the Ledger already says so in its own source.
`AccountType`'s javadoc names the four constants it deliberately omitted and why
each is missing a producer; `MerchantBalance` returns one figure where SDD 15.3
specifies four, and explains that returning `available` as zero would be a claim
rather than an omission. Settlement cannot start until `MERCHANT_AVAILABLE`
exists and something moves money into it.

That asymmetry is what the ordering below is built on: **no PR in this plan waits
on an unmerged one except PR4 on PR3**, which is a genuine data dependency rather
than a scheduling artifact.

## Why not SDD order

SDD order is Risk → Settlement → Webhook → Notification/Reporting. Following it
literally puts the hardest capability at position two and the highest
merchant-facing value at position three, queued behind it. Webhook depends on
nothing and is the thing an integrator actually notices; it goes first.

---

## The eight PRs

| # | Branch | Delivers | Depends on | Migrations | ADR |
|---|---|---|---|---|---|
| 0 | `fix/platform-admin-and-known-defects` | `PLATFORM_ADMIN` becomes grantable; the open-item-17 defect list | — | V23 | ADR-027 |
| 1 | `feature/webhook` | Endpoints, HMAC-signed delivery, backoff, replay | — | V24–V25 | ADR-028 |
| 2 | `feature/risk` | Evaluation on confirm, review queue, denylist, Redis velocity | — | V26–V27 | ADR-029 |
| 3 | `feature/ledger-available-balance` | `MERCHANT_AVAILABLE`, holding period, pending→available release | — | V28–V29 | ADR-030 |
| 4 | `feature/settlement` | Batches, items, payouts, retries, statements | PR3 | V30–V32 | ADR-031 |
| 5 | `feature/notification` | Templates, preferences, simulated sends, attempt history | — | V33 | ADR-032 |
| 6 | `feature/reporting` | Projections, summaries, async CSV export | PR4 (content) | V34–V35 | ADR-033 |
| 7 | `feature/audit` | Append-only log of privileged and financial-operational actions | PR2, PR4 (subjects) | V36 | ADR-034 |

Migration and ADR numbers are pre-assigned here so that work in parallel
worktrees cannot collide on them. This is the practice that worked in Phase 1 and
it is the reason it worked.

---

### PR 0 — Housekeeping that is in the way of everything else

**Branch:** `fix/platform-admin-and-known-defects` · **Migration:** V23 · **ADR-027**

Two things, and the first is not cosmetic.

**`PLATFORM_ADMIN` was not grantable** — closed by ADR-027; this section is kept
as written to record why PR 0 came first. `user_roles.merchant_id` was `NOT NULL`
(V2, line 91) and the primary key was `(user_id, merchant_id, role)`, so no
endpoint could produce a platform-scoped role. But merchant activation is
`PLATFORM_ADMIN`-only, and `MerchantStatusFilter` refuses every merchant-scoped
write until a merchant is `ACTIVE`. The consequence is that the Postman
collection has to **mint a token with the published dev signing secret** to get
past onboarding. That works on the `dev` profile and it is honest about being a
dev affordance, but every Phase-2 PR in this plan is supposed to be verified end
to end before merge, and every one of those runs would inherit the hack. Fixing
it once, first, is cheaper than routing around it eight times.

The fix is a nullable `merchant_id` with the primary key replaced by a partial
unique index — a platform-scoped grant is one row per `(user_id, role)` with a
null merchant, a merchant-scoped grant is unchanged. `CallerRole.PLATFORM_ADMIN`
and `NoMerchantScopeException` already exist and already expect this; nothing in
`shared/security` changes.

**Then the open-item-17 list**, which has been growing for several sessions and
is the cheapest quality win available:

- Both sweeps map their candidate rows *outside* the per-item try/catch, so one
  unmappable row disables the sweep permanently and silently (open item 2). Move
  the mapping inside.
- `ModuleBoundaryTest` allowlists by filename rather than path.
- `JwtSecretGuards` imports the guard directly, so the suite would not notice if
  it stopped being component-scanned.
- `IdentityConfiguration`'s javadoc credits the wrong class for the `Clock` bean.
- The customer API's `@Email` rejects a padded address where merchant's tolerates
  one.
- `DevelopmentSecretGuard` surfaces as a raw stack trace rather than an
  `APPLICATION FAILED TO START` block.

**Deliberately not in this PR:** the RFC-7807 divergence and the 400-vs-422
question. Both are whole-surface changes that would touch every controller and
every Postman assertion, and neither blocks anything. They belong in their own
PR or in a documented decision to keep the flat shape.

**Verification:** the Postman collection loses its token-minting request and goes
green through a real `PLATFORM_ADMIN` grant.

---

### PR 1 — Webhook

**Branch:** `feature/webhook` · **Migrations:** V24 (endpoints, events), V25 (deliveries) · **ADR-028**

The flagship of Phase 2 and the piece an integrator actually sees. SDD §18.

**Three tables, amended by ADR-028 §3 — this section originally said four.**
`webhook_endpoints` (merchant, URL, **`secret_version` rather than an encrypted
secret**, subscriptions, status), `webhook_events` (public event id unique, type,
version, payload as `text` — the *stable external* shape, deliberately not the
internal event), `webhook_deliveries` (event + endpoint unique, status, attempts,
next attempt, last status code and response excerpt).

`webhook_delivery_attempts` is **not** built. Its per-attempt forensics are
mostly restated by the counters on the delivery row, and it can be added later
without touching either table that carries an invariant. ADR-028 §3.

The secret is **derived, never stored** — `HMAC-SHA256(masterKey, info || 0x01)`
keyed on endpoint id and `secret_version`, so rotation is an integer bump and
there is no ciphertext column. This removed the cipher subsystem the original
"encrypted secret" wording would have required, and note that **JDK 21 has no
HKDF** (JEP 478/510 land it in 24/25). ADR-028 §2.

Five endpoints, per SDD §18.3: create endpoint, patch subscriptions/status,
rotate secret (shown once), list deliveries, replay a delivery.

The internal-to-external translation is the design work. `payment.succeeded` on
the outbox is PayMesh's event; `payment.succeeded` on the wire is the merchant's,
versioned, and must not leak an internal schema (SDD §18.2). A translator per
event type, and a test that the external payload of every subscribed type is
stable.

Signing reuses the shape `ProviderCallbackSignatureFilter` already establishes:
HMAC-SHA256 over `timestamp + "." + raw body`, `v1=` prefixed. It signs outbound
rather than verifying inbound, and it uses a per-endpoint secret rather than the
one global secret that open item 8 complains about — so this is the first place
in the codebase where a signing secret is genuinely per-tenant.

Delivery is a scheduled dispatcher with exponential backoff, the same shape as
the outbox relay and the simulator's callback dispatcher, and **off under `dev`**
like every other timer. An endpoint is disabled after a policy threshold of
consecutive failures and `webhook.delivery.failed` is raised.

**The invariant to protect:** a merchant endpoint being down must never affect a
payment. Delivery failure is delivery's problem.

---

### PR 2 — Risk

**Branch:** `feature/risk` · **Migrations:** V26 (rules, decisions), V27 (reviews, denylist) · **ADR-029**

SDD §14. `POST /internal/v1/risk/evaluations` returns `ALLOW`, `REQUIRE_ACTION`,
`REVIEW` or `BLOCK`, called synchronously from Payment's confirm — the one place
this PR touches existing code.

Tables: `risk_rules` (versioned expression, action, priority, enabled),
`risk_decisions` (immutable evidence: score, decision, matched rules, feature
snapshot), `risk_reviews` (analyst queue), `denylist_entries` (entity type,
hashed value, reason, expiry).

**Rules are versioned so a historical decision can be reproduced** (SDD §14.6).
A decision row stores the rule *version* it matched, not a foreign key to a row
that can later be edited. This is the same instinct as the ledger's immutable
entries.

**Velocity counters go in Redis**, per SDD §14. This is the first Redis in the
codebase — a container in compose, a Testcontainers Redis in the suite, and a
`spring-boot-starter-data-redis` dependency. It also means SDD §14.6's
requirement lands with it: **a documented fail-open/fail-closed policy by risk
tier**, because Redis being down must not take payments down with it. Low-tier
evaluation fails open on a Redis outage and records that it did; a rule that
depends on velocity and cannot read it must not silently score as if velocity
were zero.

Risk does not mutate payment status and does not decide ledger postings (§14.2).
It returns a decision; Payment acts on it.

---

### PR 3 — The Ledger grows an available balance

**Branch:** `feature/ledger-available-balance` · **Migrations:** V28 (account type, config), V29 (release job state) · **ADR-030**

This is Settlement's prerequisite, split out so that PR4 is a normal-sized PR and
so that this one ships value on its own: a merchant can see what is actually
settleable and configure when it becomes so.

- `AccountType` gains `MERCHANT_AVAILABLE` (a `CREDIT`-normal liability, like
  `MERCHANT_PENDING`), and the `ck_ledger_accounts_owner` check learns it is
  merchant-owned.
- `settlement_configs` (merchant PK, schedule, holding period, minimum amount,
  currency, payout account) — the table SDD §17.4 specifies, landing here because
  the holding period is what the release job reads.
- A scheduled release job posts a balanced transaction per eligible merchant,
  debiting `MERCHANT_PENDING` and crediting `MERCHANT_AVAILABLE` once funds are
  past the holding period. Off under `dev`, logic in a plain object taking an
  injected `Clock`, like every other timer here.
- `MerchantBalance` gains `availableMinor` and its javadoc's "all three are Phase
  2" note gets one field shorter. **This is the backwards-compatible addition
  that javadoc predicted**, which is why it was omitted rather than zeroed.
- `PUT /v1/settlement-config` and `GET /v1/settlement-config`.

Refunds are the sharp edge: a refund against a payment whose funds have already
moved to available must debit `MERCHANT_AVAILABLE`, and it must be able to drive
that balance negative rather than silently failing. `PostRefundReversalService`
learns which account to reverse against.

---

### PR 4 — Settlement

**Branch:** `feature/settlement` · **Migrations:** V30 (batches, items), V31 (payouts, attempts), V32 (constraints) · **ADR-031** · **Depends on PR3**

SDD §17, the rest of it. `settlement_batches` (period, gross, fees,
adjustments, net, status — immutable once closed), `settlement_items` (batch +
reference unique), `payouts` (batch, destination, provider id, status, attempt
count), `payout_attempts` (payout + attempt unique).

Endpoints: list settlements, read a statement, retry a payout (ops),
run a batch (scheduler/admin).

Three invariants from §17.6, and each belongs in the database rather than in a
service, following the precedent V-whatever set for debits-equal-credits:

1. **Batch net equals the sum of its items** — a deferred constraint trigger, the
   same mechanism as the ledger's balance check.
2. **Funds move to `SETTLEMENT_IN_TRANSIT` before the provider payout**, never
   straight out of available. `AccountType` gains `SETTLEMENT_IN_TRANSIT` and
   `BANK_CASH`.
3. **A final payout failure returns funds to available through a new ledger
   transaction**, never by editing the old one. Corrections are reversals
   (ADR-018).

Payout retries reuse the retry-budget shape from ADR-025: bounded attempts, a
terminal state, an alert — not infinite retry.

---

### PR 5 — Notification

**Branch:** `feature/notification` · **Migration:** V33 · **ADR-032**

SDD §19.1, and the smallest capability in Phase 2. `notification_templates`,
`notifications`, `delivery_attempts`. An `EventHandler` per subscribed event
type, a simulated sender (no real email or SMS provider — this platform moves no
real money and sends no real mail), and `GET /internal/v1/notifications/{id}` for
support diagnosis.

**Notification failure never rolls back a payment.** That is the whole design
note, and it is why this is a consumer of committed events rather than a step in
any transaction.

---

### PR 6 — Reporting

**Branch:** `feature/reporting` · **Migrations:** V34 (projections), V35 (exports) · **ADR-033**

SDD §19.2. Merchant-scoped denormalized projections built from domain events,
in PostgreSQL — **not OpenSearch**, which §19.2 permits only if search
requirements justify it and they do not yet.

`GET /v1/reports/payment-summary`, `GET /v1/reports/settlements`,
`POST /v1/report-exports` (async CSV), `GET /v1/report-exports/{id}`.

Last of the consumers deliberately, so that settlement reporting lands in the
same pass rather than needing a second one.

**Projections are eventually consistent and must say so.** Every report response
carries an `asOf` timestamp, because delivery is asynchronous (ADR-016) and a
report that reads a second stale without admitting it is worse than one that
admits it.

---

### PR 7 — Audit

**Branch:** `feature/audit` · **Migration:** V36 · **ADR-034**

SDD §19.3. `audit_events` (event id, actor type and id, merchant id, action,
resource, reason, before/after hashes, hashed IP, timestamp) and `audit_exports`.
Append-only, enforced by an immutability trigger, exactly as `ledger_entries` is.

Last because its subjects are what Phase 2 creates: risk decisions, payout
retries, secret rotations, merchant freezes, manual recovery. Building it first
would mean building a log of things that have not happened yet.

**Financial journals stay in the Ledger; operational and security history lives
here.** The two are not merged and the ADR should say why.

---

## Out of scope for Phase 2

- **AI Operations (SDD §20).** It is explicitly gated on operational and
  reporting data existing, which is PR6. It is Phase 3.
- **Kafka (SDD §22).** ADR-016 chose in-process dispatch deliberately and nothing
  in Phase 2 needs more. Extracting services is what needs Kafka, and extraction
  is not Phase 2.
- **The RFC-7807 error-shape divergence** and 400-vs-422. Documented, deliberate,
  whole-surface, and blocking nothing.
- **Per-provider callback credentials** (open item 8). Still one global secret.
  PR1 makes per-tenant secrets real for outbound webhooks, which moves this
  closer without closing it.

## Working method, per PR

The Phase 1 method, unchanged, because it worked:

1. A design spec written and approved before implementation, and corrected in the
   spec when it turns out wrong — not just in the code.
2. One capability per branch, one focused change per PR.
3. Verified live before merge, including the Postman collection, because the Java
   suite cannot see HTTP-surface regressions. Open item 16 is the proof: 1176
   tests stayed green while the product became unusable from outside.
4. Nothing merges on the author's report. An independent reviewer re-runs the
   suite and, where a test protects an invariant, **breaks the implementation to
   confirm the test catches it**. A green assertion that never fails is worse
   than no assertion.
5. Every non-obvious tradeoff gets an ADR while the reasoning is fresh.
