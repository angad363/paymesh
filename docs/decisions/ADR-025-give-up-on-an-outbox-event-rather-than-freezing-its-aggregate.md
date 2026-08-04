# ADR-025: Give up on an outbox event rather than freezing its aggregate

- **Status:** Accepted
- **Date:** 4 August 2026
- **Amends:** [ADR-016](ADR-016-in-process-event-dispatch-before-kafka.md) — closes the residue
  it recorded and could not close at the time
- **Related:** SDD §22.3–§22.4 (outbox/inbox), §24 (durability and alerting), §26 (observability),
  `docs/project-status.md` open item 14
- **Migration:** V21

---

## 1. The defect

Since ADR-016 the relay has retried a failed event **at the head of every pass, forever**, and
deferred that aggregate's later events behind it so ordering holds. ADR-016 recorded the residue
plainly:

> The residue, stated rather than discovered later: an event that fails FOREVER freezes its
> aggregate's later events forever. […] There is no dead-letter table, no attempt counter and no
> alert.

Open item 14 called this "the largest known hole in event delivery". Three things made it worse than
it sounds:

1. **The freeze is per-aggregate and permanent.** One poisoned `payment.succeeded` means that
   payment's every subsequent event — `payment.cancelled`, `refund.succeeded`'s downstream effects —
   is withheld indefinitely. The Ledger never posts. The order never leaves `PENDING`.
2. **It is silent.** The only signal was one WARN per pass, in a job that logs at INFO only when it
   did something, on a platform with no log aggregation.
3. **The relay could not tell the difference between a first failure and a nine-hundredth.** Nothing
   was written down, so every pass started from zero knowledge. There was no way to *act* on
   "this is never going to work" because the relay could not know it.

The third point is why this stayed open. "Add a dead letter" reads like policy; it was blocked on
the relay having a memory at all.

## 2. Decision

**Count failures on the row, and stop claiming a row once the count reaches a configured budget.**

V21 adds four columns to `outbox_events`: `attempt_count`, `last_attempt_at`, `last_error`,
`dead_lettered_at`. The relay records every failure in one statement, and the attempt that reaches
`paymesh.events.outbox-relay.max-attempts` (25, ≈ one minute at the 2s interval) stamps
`dead_lettered_at`. The claim query gains `and dead_lettered_at is null`, so **the aggregate the
event was blocking drains on the very next pass.**

The trade is stated without softening: a dead-lettered event is **never delivered**. It is retained
in place, in order, fully readable, and requeued by clearing the stamp. That is worse than delivery
and much better than an aggregate whose entire future is withheld because of one predecessor.

## 3. Why columns and not a dead-letter table

The obvious shape is a second table failed events are *moved* to. Refused for the reason V7 refused
a status column: **the move is a second copy of a fact this table already holds**, and two rows
describing one event can disagree.

Two consequences follow from not moving the row:

- **Requeueing preserves order.** A moved row would lose its place in `occurred_at` sequence, so
  putting it back would deliver an aggregate's events out of sequence — the exact guarantee the
  relay defers events to protect.
- **`dead_lettered_at IS NULL` is the same status model `published_at IS NULL` already is.** One
  idiom, no status column, nothing to disagree with.

## 4. Why the increment and the decision are one SQL statement

`attempt_count + 1` appears in both the `SET` and the `CASE`, so the value compared against the
budget is by construction the value being stored. Read-decide-write across two statements would
leave a window in which a second relay instance burns an attempt while recording none.

The statement also carries `and published_at is null`, making it a compare-and-swap on the same
column `markPublished` swaps: a delivery that succeeded between the dispatch failing and the attempt
being recorded wins over a failure that has since been superseded. Without it, an event could be
marked both delivered and abandoned, and the alert would raise an incident that never happened.

## 5. Why the alert is a health indicator and not metrics

SDD §26 wants OpenTelemetry, Prometheus and Grafana. None exists, and reading "add an alert" as
"first build an observability stack" is part of why this item stood unclosed.

`/actuator/health` is **already exposed** and is already what anything watching this application
polls. `OutboxBacklogHealthIndicator` reports DOWN on either of two conditions:

- **dead-lettered events exist** — a committed state change no consumer will ever hear about. This
  does not heal, and the ERROR the relay logged scrolled past days ago.
- **the oldest deliverable event is older than `backlog-alert-age`** — SDD §24's own metric, and the
  only thing that distinguishes "keeping up" from "the relay stopped an hour ago". *A relay whose
  timer is off is otherwise completely silent: no errors, no logs, nothing. Only the age moves.*

Building a metrics pipeline to carry two numbers would have been building the pipeline, not the
alert. When Prometheus lands these become gauges and the indicator stays — a gauge nobody wrote a
rule for is not an alert either.

### 5.1 The sharp edge, recorded rather than discovered during an incident

DOWN makes the aggregate `/actuator/health` return 503. That is the intent. **It also means this
indicator must never be wired into a Kubernetes liveness or readiness probe** when SDD §27's
deployment work lands: restarting the application does not deliver a dead-lettered event, and
draining an instance because its backlog is old removes the process that was working through the
backlog. It belongs in a health group that alerting scrapes and orchestration ignores.

## 6. Why 25 attempts

Read it as a duration, not a count: at the 2s relay interval an event survives roughly a minute of
continuous failure. That comfortably outlives a consumer restart, a lock timeout or a brief database
blip — the transient causes — while a genuinely poisoned event stops blocking its aggregate in about
a minute rather than never.

There is deliberately **no value meaning "retry forever"**. That was the old behaviour, and it is the
bug. A budget below 1 is rejected at construction rather than clamped: silently correcting it would
hide a misconfiguration that costs delivery.

## 7. Consequences

- A permanently failing event no longer freezes its aggregate. **Open item 14 is closed.**
- An abandoned event is a real loss of delivery, surfaced three ways: one ERROR carrying the requeue
  statement, a `deadLettered` count on every pass, and a health indicator that stays DOWN until an
  operator acts.
- `attempt_count` is mapped read-only on the entity; the other three columns stay unmapped, like
  `published_at`. The entity is `@Immutable`, so all four are maintained natively.
- Recording an attempt is best-effort: if the bookkeeping write throws — most likely because the
  database is down, which is also why the delivery just failed — the pass continues. Rethrowing
  would let a bookkeeping failure abort the sweep over every other aggregate, which is the
  one-bad-row-kills-the-job shape open item 2 records.
- **Still not closed:** `outbox_events` and `processed_events` are never pruned; `occurred_at` is
  not unique, so two events for one aggregate in the same instant have no defined order; there is
  one relay instance with no leader election.

## 8. Alternatives considered

**Retry forever with an attempt counter and an alert only.** Surfaces the problem without ever
dropping delivery. Rejected: it leaves the aggregate frozen, which is the actual defect. The counter
would tell an operator about a freeze they still could not clear without hand-editing the row —
which is the requeue statement, run in the opposite direction.

**Exponential backoff instead of a budget.** Reduces wasted work but never ends the freeze; the
aggregate is still blocked, just more politely. Worth adding later as an efficiency change.

**Deliver out of order after N failures.** Drains the aggregate without losing an event, at the cost
of the ordering guarantee — a consumer could see a payment's outcome before the payment. Rejected:
ADR-016 chose to defer rather than reorder precisely because out-of-order delivery is worse than
none, and nothing here changes that judgement.
