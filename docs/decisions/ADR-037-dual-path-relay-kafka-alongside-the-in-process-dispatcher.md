# ADR-037: The dual-path relay — Kafka alongside the in-process dispatcher, deduped by the same inbox

- Status: Accepted
- Date: 2026-08-25
- Scope: PayMesh Phase 3A, PR 2. No migration. SDD §22.1. Follows ADR-036.

## Context

ADR-036 built the transport and the wire contract — `EventEnvelope`,
`KafkaEventPublisher`, the topic and partition rules — and deliberately gave them
no caller. The relay still hands every event to the in-process `EventDispatcher`
(ADR-016), and consumers still dedupe through their own `processed_events` row.
The app starts and the whole suite passes with no broker.

This PR gives the publisher a caller and the topics a consumer, **without
removing the in-process path**. By the end of it every event that flows in
process also flows through Kafka, and a Kafka listener feeds the same handlers
through the same inbox — so the two paths together apply an event exactly once,
and either path alone is sufficient. That redundancy is the point: it is the
safety net that makes every later extraction (PRs 6–14) reversible by a flag
rather than a redeploy.

ADR-036 closed on the one thing this PR must decide before it publishes anything:

> an unreachable broker would burn a row's whole budget in under a minute and
> dead-letter the backlog for an outage no operator would call long. [...] a
> dependency that is simply down is a different failure and needs a different
> answer. [...] it is the first thing ADR-037 has to decide.

It is decided in §3.

## Decision

### 1. One flag, three-valued in intent, two-valued in code: `paymesh.events.delivery.mode`

`in-process` | `both`, defaulting to **`both`**. It governs two things at once so
they can never disagree:

- the relay's **producer** side — whether it also publishes to Kafka after the
  in-process dispatch;
- the **consumer** side — whether the Kafka listener bean is registered at all
  (`@ConditionalOnProperty`).

`both` is the default because the whole PR exists to run both paths. **The `dev`
profile overrides it to `in-process`** — that profile is what the test suite runs
under, and `in-process` is exactly the pre-ADR-036 behaviour, so the ~450
integration tests keep passing with no broker anywhere. This is the same split
ADR-036 used for the round-trip test: the one test that needs a broker starts its
own (`DualPathRelayIntegrationTest`, Testcontainers), and nothing else pays for
one.

A third `kafka`-only mode is **not** built. It is where each capability lands
when it is extracted (PRs 6–14 turn off the monolith's in-process copy per
capability) and where PR 16 leaves the whole system, but nothing needs it in this
PR and a mode with no caller is configuration debt. `both` and `in-process` are
the two this PR can actually exercise, and they are the two the rollback needs.

### 2. The producer: in-process first, then Kafka, both before the stamp

The relay's per-item loop is unchanged except for one added step. It dispatches
in process exactly as before, and **then**, in `both` mode, publishes to Kafka;
only when both sinks have accepted the event does it stamp `published_at`. The
order matters: in-process stays first so that Kafka being a sink can never delay
or block the authoritative money-path delivery, and `published_at` still means
"delivered to everyone" — now including the broker — so there is **no new column**
(the plan's "DB: none").

Because both sinks run before the stamp, a redelivery re-runs both: the
in-process dispatch finds its inbox rows already claimed and is a no-op, and the
Kafka publish is retried. The producer's own idempotence (`enable.idempotence`,
ADR-036) collapses a retried send that actually succeeded, and any duplicate that
still slips through is absorbed by the consumer inbox. At-least-once on both
sides, exactly-once nowhere — ADR-016's stance, now across a wire.

### 3. The dead-letter budget governs the in-process sink only; a broker outage is not a poison

This is ADR-036's deferred question. The retry budget and dead letter (ADR-025)
exist for **one** failure mode: an event whose *content* a handler can never
apply, which without a budget blocks its aggregate's later events forever. That
is a property of the event.

A broker being unreachable is the opposite kind of failure. It is **global** (it
fails every event equally, not one poisoned aggregate) and **self-healing** (every
event succeeds the moment the broker returns). Counting it against a
per-event budget sized at 25 attempts — under a minute at the 2s relay interval —
would dead-letter healthy events for a blip, turning the safety net into the
fault. So:

- **An in-process dispatch failure consumes the budget and can dead-letter,
  exactly as today.** That is where a poison manifests, and nothing about it
  changes.
- **A Kafka-sink failure does not consume the budget and never dead-letters.**
  The row is left unpublished and retried on the next pass — indefinitely, until
  the broker accepts it. What makes "indefinitely" safe here, where it was the
  ADR-025 bug for handlers, is that a transport failure singles out no aggregate
  (so it blocks nothing that the outage itself was not already blocking) and
  resolves without human action (the broker comes back). What makes it *visible*
  is the age-based backlog health indicator (ADR-025): an unpublished row's age
  grows past `backlog-alert-age` and `/actuator/health` goes DOWN, whether the
  cause is a stalled relay or a stalled broker. The alert that already exists is
  the right alert for this too.

The consequence, stated plainly: in `both` mode a Kafka outage stops nothing on
the money path — in-process has already delivered — and it does not corrupt the
outbox; it only ages the backlog until the broker returns or an operator flips
the flag to `in-process` (§5). The one failure this rule does *not* cover is an
event that can never be **addressed** (an `aggregateType` that cannot form a legal
topic, ADR-036 §3). That is a genuine poison, but it is unreachable by
construction — every `aggregateType` in the system is an `UPPER_SNAKE` constant
that forms a legal topic — and if one ever occurred it would age the backlog
loudly like any other stuck row rather than fail silently. Building a second,
content-aware budget for a row that cannot exist is exactly the speculative
machinery this phase is meant to avoid.

### 4. The consumer: one listener, the existing dispatcher, the existing inbox

The plan says "each `EventHandler` gets a Kafka listener adapter." The literal
reading is one listener per handler; the **lazier and identical** implementation
is **one** `@KafkaListener` that subscribes to every event topic by pattern
(`.+-events`, which every topic matches by ADR-036 §3) and hands each record to
the `EventDispatcher` the in-process path already uses. The dispatcher *is* the
fan-out: it already routes an event to every handler subscribed to its type and
dedupes each through `processed_events`. A per-handler listener would rebuild that
fan-out N times over. So the Kafka path is:

```
record → EventEnvelope.toEvent() → EventDispatcher.dispatch(event)
```

and every consequence the dispatcher gives the in-process path — one transaction
per (handler, event), the inbox claim, throw-to-retry — is inherited unchanged.
A handler that throws does not commit its offset, so the record is redelivered and
the handler retried while its already-succeeded siblings dedupe: the same
per-handler independence the relay has, now on the broker.

**One consumer group** for the monolith (`paymesh-monolith`), stable because a
rename replays the topic from `earliest` (ADR-036) — safe, since the inbox dedupes
the replay, but wasteful, so it is chosen once. When a capability is extracted it
gets its *own* group and consumes independently; the monolith's group is the
transitional single reader.

Consumer-side error handling is Spring's default (`DefaultErrorHandler`: a bounded
retry, then log and advance). That is sufficient **because in `both` mode the
in-process consumer, with its inbox and its dead-letter budget, remains the
authoritative one** — a record the Kafka consumer gives up on was already applied
in process. A real consumer-side dead-letter topic belongs to a capability once it
is Kafka-*only* (it has no in-process fallback then), so it is built per service
during extraction, not here. Marked as a ceiling in the code.

### 5. Rollback is the flag, and it is a behavioural no-op

Setting `paymesh.events.delivery.mode=in-process` returns the system to exactly
ADR-036's state: the relay dispatches in process and does not touch Kafka, and the
listener bean is not registered so nothing consumes. Because `both` mode changed
**nothing** about the in-process path — same order, same budget, same stamp — the
flip is a no-op on the money path, not a recovery from one. That is the property
that makes it a safe rollback rather than a second failure mode.

## What is deliberately NOT built

- **No `kafka`-only mode.** §1. It arrives with the first extraction that needs it.
- **No new schema.** §2. `published_at` already means "delivered to all sinks";
  Kafka is one more sink under the same column.
- **No per-handler Kafka adapter, no per-topic listener list.** §4. The dispatcher
  is the fan-out and the topic pattern is the subscription; a hand-maintained topic
  list would be a second place to forget an aggregate.
- **No consumer-side dead-letter topic, no `spring-retry` topics.** §4. It belongs
  to a Kafka-only capability, built at extraction.
- **No transport-vs-content taxonomy on the producer.** §3. The one content-poison
  a Kafka send could raise is unreachable, and a row that ages loudly needs no
  second budget to be caught.

## Consequences

- **From this PR the default deployment needs a broker to be fully delivering.** In
  `both` mode a missing broker does not stop the app starting (a listener container
  retries its connection; the producer blocks-then-fails and the relay retries) and
  does not lose committed money movement (in-process still delivers), but the Kafka
  half of delivery ages the backlog until the broker is up. `docker compose up -d
  kafka` provides it; the `dev` profile opts out entirely.
- **In `both` mode every event is delivered twice and applied once.** The extra
  work is a second inbox-claim per (handler, event) that reads "already processed"
  and does nothing. That is the measured cost of running a safety net, paid only
  during the transition.
- **The suite is unchanged in shape.** Every existing `@SpringBootTest` runs `dev`
  → `in-process` → the pre-existing behaviour, no broker. One new Testcontainers
  test runs `both` and proves the round trip through the relay and back through a
  handler.
- **`RelayResult.failed` now spans both sinks.** A Kafka-sink failure is counted
  there (it threw and was logged and will be retried) but never feeds
  `deadLettered`, so the invariant `deadLettered ⊆ failed` holds and the money-path
  budget metric still means only what it meant.

## Identifiers

No new identifier type. `EventId` (`evt_`, ADR-003) remains the wire-level
deduplication key on both paths.
