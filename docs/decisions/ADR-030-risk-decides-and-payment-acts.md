# ADR-030: Risk decides, Payment acts — and the rules are code, not data

**Status:** Accepted
**Date:** 2026-08-06
**Implements:** SDD §14 (Risk), partially and deliberately.

## Context

Phase 2's plan called for Risk as four tables — `risk_rules` holding versioned expressions,
`risk_decisions`, `risk_reviews` for an analyst queue, `denylist_entries` — plus Redis for velocity
counters and a documented fail-open policy for when Redis is down.

That is a faithful reading of SDD §14. It is also a great deal of machinery for a capability whose
job, today, is to answer one question at one call site: may this confirm proceed?

## Decision

**Risk returns a decision; Payment acts on it** (SDD §14.2). Risk writes nothing to any payment
table, has no opinion about status, and emits no event. That separation is the load-bearing one: a
Risk service that could fail a payment would be a second author of Payment's state machine, and two
authors of one state machine is how a status becomes unexplainable.

Evaluation happens in `ConfirmPaymentIntentService`, **immediately before it opens its
transaction** — see the consequences below for why that placement, which looks like the weaker
one, is the correct one.

### Three things from the plan were not built

| Not built | Why | What would change that |
|---|---|---|
| `risk_rules` with stored expressions | Needs an evaluator, a syntax, and a bad expression taking the money path down at *runtime* rather than compile time. It buys one thing: changing a rule without a deploy. | Someone needs a rule *shape* nobody anticipated. Thresholds moving to config covers most real demand first. |
| Redis velocity counters | A container, a Testcontainers dependency, a fail-open policy and a whole outage mode on the money path, for a count PostgreSQL computes from rows it already has. | The query appears in slow logs. That is a measurement, not a guess. |
| `risk_reviews` queue | Nothing reads one. A queue's shape is decided by how it gets worked. | An analyst surface exists. |

Also not built: SDD §14's `REQUIRE_ACTION` outcome. It means "step the customer up to 3DS", and
PayMesh has no step-up for Risk to ask for. This codebase has spent three ADRs (021, 024, 027)
making unreachable enum constants reachable; minting a new one would be going backwards.

**Dropping Redis also removes SDD §14.6's fail-open requirement rather than skipping it.** With no
second system there is nothing to be down. The evaluation either succeeds or the confirm fails
before it starts — fail *closed*, and correct here precisely because the "outage" case would mean
the database is not answering, in which case there is no confirm to protect either. If Risk ever
becomes a network call, that reasoning inverts and this is the line to revisit.

### Reproducibility without a rules table

SDD §14.6 requires that a historical decision be explainable. That needs the **inputs** and the
**rule version** stored with the outcome — not the rule to have been data. A decision re-derived
from a live rule table is not evidence of what happened; it is a guess about what would happen now.

So `RiskRuleset.VERSION` is stamped on every row alongside a verbatim feature snapshot, and the
ruleset is a pure function of that snapshot. **Bump the version on any behavioural change.** Not
bumping it is the one way to make this audit trail lie, which is why the velocity window is the
only tunable exposed in configuration: it is an *input*, so a change to it is visible in every
affected row rather than invisible in all of them. A threshold in a YAML file could be changed by
an operator who has never heard of the version constant.

## Consequences

### Two defects the unit tests structurally could not find

**The velocity count included the intent it was judging.** That intent is created inside the same
window moments before the confirm, so a plain count always returned the subject of the question:
every payment scored one higher than it should, and every threshold fired a confirm early.
`EvaluateRiskServiceTest` stubs the lookup with a hand-set number, so the real predicate never ran
there. It took an integration test asserting that a fresh customer's first confirm scores **zero**.

**And the assessment did not survive a block.**

It wrote the assessment inside the confirm's transaction, and a `BLOCK` makes that transaction
throw — so the evidence rolled back with the confirm it refused. The merchant got
a 422 naming an assessment id, and the row that id pointed at did not exist.

No unit test could see this: they have no real transaction to roll back. It took a
`@SpringBootTest` against real PostgreSQL asserting that the row survives.

The first fix was `REQUIRES_NEW` on the repository save. It worked, and it was the wrong answer:
Spring suspends the enclosing transaction **without releasing its connection**, so every confirm
would have held two pool connections at once while a row lock was live. Nothing configures Hikari
here, so the pool is the default ten — roughly five concurrent confirms and every thread waits for
a second connection only another waiting thread can release. A wedged pool for the whole
application, not a slow payment.

The right fix was to stop nesting. Risk is evaluated **before** `ConfirmPaymentIntentService` opens
its transaction, so the assessment is enclosed by nothing and commits on its own. One connection at
a time, and the evidence survives because there is no outer transaction to roll it back.

Nothing is lost by reading the intent unlocked for the evaluation: every feature Risk looks at —
amount, currency, customer — is fixed at creation and no transition can change it. The denylist can
change in the microseconds before the lock, and an entry added in that window applying to the *next*
attempt rather than this one is correct behaviour anyway.

**This means an assessment records that an evaluation happened, not that the payment proceeded.**
That is the correct reading, and it is why the row carries no payment status.

### A blocked payment is refused, not failed

`PaymentBlockedByRiskException` → 422, and the intent stays `REQUIRES_CONFIRMATION`. A denylist
entry is a live opinion an operator can retract — entries carry an expiry for exactly that — so
burning the intent for a decision that may be reversed in a minute is the harsher of the two
defaults. The merchant retries after the entry is removed and it works.

The cost, stated: a payment PayMesh is suspicious of stays live and keeps occupying
`uq_payment_intents_live_per_order` until it expires or is cancelled. The abandoned-intent sweep
already frees exactly that, which is what makes this affordable.

### The error body names the assessment and never the rule

An error that says *which* rule refused a payment is a free oracle: retry, vary one input, watch
the message change, and the ruleset is mapped. The `rsk_` id lets support answer "why was this
refused?" from the database, where the reasons belong. Same instinct as `ORDER_NOT_PAYABLE`
collapsing three causes into one code.

`RiskCheck.Decision` therefore carries a boolean and an id — the matched rules stay on Risk's side
of the module boundary by construction, not by discipline.

### Denylist values are hashed, and the honest version of why

Unsalted SHA-256, because the only operation this table supports is an equality match and a salt
would make it unlookupable. These values are low-entropy, so this does **not** resist an attacker
with a candidate list. It resists a table dump and an operator's idle browsing, which is the actual
threat at this size. Encryption with a managed key is the answer to the other one, and ADR-006
still owns that question.

### The boundary test had a hole exactly where the new capability went

`PaymentModuleVelocityLookup`'s first draft declared its own `JpaRepository` over Payment's
`PaymentIntentJpaEntity` — reaching past Payment's application layer into its table. Every other
cross-module adapter here routes through the owning module's application service, and
`ModuleBoundaryTest`'s own javadoc names this exact shortcut as the thing it refuses.

It could not refuse it: `CAPABILITIES` listed eight modules and `risk` was not among them. **A new
capability is precisely when that list is stale, and precisely when nobody thinks to check it.**
The count now lives on `GetPaymentIntentService`, where it belongs, and `risk` is in the list — so
the next module to try this gets caught.

### One index on someone else's table

V27 adds `idx_payment_intents_merchant_customer_created`. V8 indexed `(merchant_id, created_at)`
and `(merchant_id, order_id)` but nothing on customer, so the velocity count would have been a scan
of the merchant's whole history — taken while the confirm holds the intent's row lock. On demo data
that is invisible; on real volume it is a lock held for a table scan, which is the shape of an
outage rather than a slow query. The port's javadoc originally *claimed* an index existed. Claiming
is not checking.

### Known limits

- Velocity counts intents **created** in the window, not confirms — an abandoned checkout is still
  part of the pattern. Named for what it counts rather than what it approximates, and the intent
  being judged is excluded from its own count (see below).
- There is no `GET /risk/assessments` route yet. The data is queryable and the read port exists;
  the surface can come with the analyst work that needs it.
- `REVIEW` and `ALLOW` behave identically today. The difference is the recorded evidence, which is
  a question an operator can ask. **Do not make REVIEW block** while nothing works a queue — a
  blocking REVIEW strands the payment forever, which is worse than the risk it hedges.
