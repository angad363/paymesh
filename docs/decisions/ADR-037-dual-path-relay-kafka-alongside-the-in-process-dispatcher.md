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

### 2. Two independent sink tracks, one per column — because a shared gate stalls the money path

The producer is **two passes over two columns**, not one loop delivering to two
sinks. `PublishOutboxEventsService.publish()` is the in-process pass, unchanged
from before this PR: it claims `published_at IS NULL`, dispatches to the handlers,
stamps `published_at`. `relayToKafka()` is a separate pass: it claims
`kafka_published_at IS NULL` (V37), publishes to the broker, stamps
`kafka_published_at`. The timer runs both each tick.

**A single shared `published_at` would stall the money path, and this is the
sharp lesson of the PR.** The first cut of this ADR gated one column on both
sinks: dispatch in process, then publish to Kafka, then stamp. It has a
money-path bug. When the broker is down, a Kafka failure leaves the row
unstamped so it retries — but the row was *already delivered in process*, and it
now sits at the head of the oldest-first, bounded in-process claim. During a
sustained outage the batch **saturates** with in-process-done, Kafka-pending
rows, and newly committed events are never claimed: **a Kafka outage stalls
in-process delivery** — the Ledger stops posting captured payments while the
broker is unreachable. The invariant this whole phase protects, broken by the
safety net meant to protect it.

Two columns are the fix, and they are worth a migration (V37) despite the plan's
"DB: none": an in-process-delivered row leaves the in-process claim the instant
`published_at` is stamped, regardless of Kafka, so the money path never waits on
the broker. The two tracks retry on their own clocks; the inbox (in-process) and
the idempotent producer + oldest-first order (Kafka) each keep their own
at-least-once delivery. `published_at` still means exactly what V7 said —
"delivered in process" — and `kafka_published_at` is its independent twin.

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

- **The in-process pass keeps the budget, exactly as today.** That is where a
  poison manifests — a handler that can never apply an event — and nothing about
  it changes. It lives on `published_at` and its dead-letter machinery.
- **The Kafka pass has no budget at all.** A send failure leaves
  `kafka_published_at` NULL and is retried next tick — indefinitely, until the
  broker accepts it — and is never dead-lettered. What makes "indefinitely" safe
  here, where it was the ADR-025 bug for handlers, is that a transport failure
  singles out no aggregate (its own two-column separation means it blocks nothing
  in process) and resolves without human action. What makes it *visible* is a
  second age on the same backlog health indicator: `oldestUnpublishedToKafka`,
  reported alongside `oldestUnpublished`. It does **not** flip the endpoint to
  DOWN, deliberately — Kafka has no consumer that depends on it yet, so a broker
  outage must not page as though the in-process relay had stopped; it is promoted
  to a DOWN condition when a service is extracted onto the stream.

The consequence, stated plainly: in `both` mode a Kafka outage stops nothing on
the money path — the two columns are the guarantee — and it does not corrupt the
outbox; it only ages the Kafka backlog until the broker returns or an operator
flips the flag to `in-process` (§5).

**Two ceilings this leaves, both money-safe and both marked in the code:**

- **The blocking send runs on the shared relay thread.** `relayToKafka` ends at
  the first send failure (there is no point paying the acknowledgement block for
  every following aggregate when the broker is down) and retries next tick, but it
  still shares the scheduler thread with the in-process pass, so a broker outage
  can delay the next in-process *tick* — a latency degradation of the money path,
  never a loss or a reorder. A dedicated Kafka relay thread is the upgrade path,
  deferred to when a consumer depends on the stream.
- **A permanently un-sendable event freezes only its Kafka stream.** An
  `aggregateType` that cannot form a legal topic (ADR-036 §3) is unreachable by
  construction — every one is an `UPPER_SNAKE` constant — but were it to occur it
  would retry forever on the Kafka track, ageing `oldestUnpublishedToKafka` loudly
  while the money path (a separate column) is untouched. A content-aware Kafka
  dead-letter is deferred for the same reason: it guards a row that cannot exist,
  and the money path no longer depends on it.

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
- **No shared status column.** §2. The one migration this PR *does* take
  (`kafka_published_at`, V37) is the whole point — a single `published_at` gating
  both sinks stalls the money path during a Kafka outage, so the two sinks get two
  columns and two claims.
- **No per-handler Kafka adapter, no per-topic listener list.** §4. The dispatcher
  is the fan-out and the topic pattern is the subscription; a hand-maintained topic
  list would be a second place to forget an aggregate.
- **No consumer-side dead-letter topic, no `spring-retry` topics.** §4. It belongs
  to a Kafka-only capability, built at extraction.
- **No dedicated Kafka relay thread, no content-aware Kafka dead-letter.** §3. Both
  are money-safe ceilings marked in the code — the first a latency degradation
  during an outage, the second a guard for a row that cannot exist — deferred to
  when a consumer depends on the Kafka stream.

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
- **The two passes report separately.** `RelayResult` (in-process) is unchanged,
  so its `failed`/`deadLettered` still mean only what they meant. `relayToKafka`
  returns its own `KafkaRelayResult`, which has no `deadLettered` at all — the
  Kafka sink has no budget — and the timer logs the two lines apart.

## Identifiers

No new identifier type. `EventId` (`evt_`, ADR-003) remains the wire-level
deduplication key on both paths.
