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

Evaluation happens inside `ConfirmPaymentIntentService`'s transaction, after the intent's row lock
and before the status moves.

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
second system there is nothing to be down. The evaluation either succeeds inside the confirm
transaction or the confirm fails as a unit — which is fail *closed*, and correct here precisely
because the "outage" case would mean the database is not answering, in which case there is no
confirm to protect either. If Risk ever becomes a network call, that reasoning inverts and this is
the line to revisit.

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

### The integration test found a defect the unit tests structurally could not

`EvaluateRiskService` wrote the assessment inside the confirm's transaction, and a `BLOCK` makes
that transaction throw — so the evidence rolled back with the confirm it refused. The merchant got
a 422 naming an assessment id, and the row that id pointed at did not exist.

No unit test could see this: they have no real transaction to roll back. It took a
`@SpringBootTest` against real PostgreSQL asserting that the row survives.

Fixed with `REQUIRES_NEW` on the repository save, the same shape the idempotency record uses for
the same reason — commit the record before the thing it is about is allowed to fail. **This means
an assessment records that an evaluation happened, not that the payment proceeded.** That is the
correct reading, and it is why the row carries no payment status.

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

### One index on someone else's table

V27 adds `idx_payment_intents_merchant_customer_created`. V8 indexed `(merchant_id, created_at)`
and `(merchant_id, order_id)` but nothing on customer, so the velocity count would have been a scan
of the merchant's whole history — taken while the confirm holds the intent's row lock. On demo data
that is invisible; on real volume it is a lock held for a table scan, which is the shape of an
outage rather than a slow query. The port's javadoc originally *claimed* an index existed. Claiming
is not checking.

### Known limits

- Velocity counts intents **created** in the window, not confirms. Near-identical as a signal, and
  named for what it counts rather than what it approximates.
- There is no `GET /risk/assessments` route yet. The data is queryable and the read port exists;
  the surface can come with the analyst work that needs it.
- `REVIEW` and `ALLOW` behave identically today. The difference is the recorded evidence, which is
  a question an operator can ask. **Do not make REVIEW block** while nothing works a queue — a
  blocking REVIEW strands the payment forever, which is worse than the risk it hedges.
