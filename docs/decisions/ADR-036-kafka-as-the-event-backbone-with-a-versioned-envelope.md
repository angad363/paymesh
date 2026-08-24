# ADR-036: Kafka as the event backbone, carrying the outbox row as a versioned envelope

- Status: Accepted
- Date: 2026-08-24
- Scope: PayMesh Phase 3A, PR 1. No migration. SDD §22.1.

## Context

Phase 3 turns the modular monolith into nine services
(`docs/phase-3-microservices-extraction-plan.md`). Every one of them needs a way
to tell the others what happened, and the plan's first rule forbids the
alternative: no distributed transaction, ever, so a service commits its own
database and its own outbox row and everything beyond that is eventual.

The producing half of that already exists. Since ADR-010 every state change has
committed an `outbox_events` row in the caller's transaction; since ADR-016 a
relay drains it into an in-process `EventDispatcher` and every consumer dedupes
through its own `processed_events` row. `EventHandler`'s contract — an envelope
in, a `Map<String, Object>` payload, idempotent, throw to retry — was written to
be a broker's contract before there was a broker, precisely so this PR would not
have to rewrite a single consumer.

What is missing is the transport and, less obviously, **a contract for what goes
on it**. In one process the envelope is a Java record that both sides compile
against; the compiler is the schema. Across a wire there is no compiler, and the
first service that renames a field breaks eight others at runtime.

This PR builds the transport and that contract, and **nothing uses either**. The
relay still hands events to the in-process dispatcher. PR 2 (ADR-037) is what
gives the publisher a caller.

## Decision

### 1. Kafka (KRaft), one broker in dev, three in the target

Kafka rather than RabbitMQ or Postgres-as-a-queue, for one reason that matters
here: **retained, replayable, partitioned log semantics.** A new service — and
this phase creates eight of them — can be pointed at a topic and consume history
it was not running for. A queue would have thrown that history away on
acknowledgement, and rebuilding a new service's read model would mean a bespoke
backfill each time. Replay is also how a consumer recovers from its own bug: fix
the handler, delete its `processed_events` rows, re-read the topic.

KRaft, so there is no ZooKeeper to run, and the dev broker is one container that
is both broker and controller (`docker-compose.yml`). Three brokers is the
target, and the only thing the single-broker dev box changes is replication
factor — the producer settings below are written for the target, not for the
laptop.

### 2. The envelope is the outbox row, formalized — not a new event model

`EventEnvelope` carries exactly what `outbox_events` has carried since V7:
`eventId`, `eventType`, `eventVersion`, `occurredAt`, `merchantId`,
`aggregateType`, `aggregateId`, `payload`. No field is added and none is dropped.
Phase 3 changes where events go, not what an event is.

It is nonetheless a **separate type** from `OutboxEvent`, and the distinction is
the same one ADR-028 draws between Webhook's domain model and its wire payload:

- `OutboxEvent` is internal. It holds validated value objects (`EventId`,
  `MerchantId`) and its field names are ours to rename freely.
- `EventEnvelope` is a published contract. It holds plain strings, and its field
  names are parsed by services we do not deploy.

Serializing the domain type directly would have made every refactor of it a
breaking change to nine consumers, and would have put `{"value":"evt_..."}` on
the wire, because that is what a single-component wrapper record serializes to.

The payload stays an open `Map<String, Object>` rather than becoming a typed
schema per event. That is what keeps a consumer from importing the producer's
domain types, which is the property `ModuleBoundaryTest` protects today and that
an extracted service must keep once no compiler is checking it.

### 3. Topic per aggregate, named by the event type's domain prefix

`order.created` → `order-events`. `payment.succeeded` → `payment-events`.
`customer.payment_method.attached` → `customer-events` (only the first segment
counts). The `eventType` field discriminates within the topic, exactly as it
already discriminates within the outbox table.

**Per aggregate, not per service**, because this phase moves capabilities between
deployables repeatedly and a topic named after a service would have to be renamed
each time — a rename that means republishing history or living with two topics.
An aggregate does not move.

**Derived, not configured.** The rule is a function of the event type and lives
in exactly one place (`EventEnvelope.topic()`). A registry mapping event types to
topics would be a second place to update and a second place to forget; adding an
event type should require no topic configuration at all. An event type with no
`domain.` prefix is a producer bug and throws rather than quietly creating a
topic nobody consumes.

It is the `eventType` prefix rather than `aggregateType` because the prefix is
already the domain name a reader expects — `payment.succeeded` belongs on
`payment-events`, not on the `payment-intent-events` its aggregate type
(`PAYMENT_INTENT`) would have produced.

### 4. Partition key is the aggregate id

Kafka orders records within a partition and promises nothing across partitions,
so the key chooses what is ordered. The key is `aggregateId`.

The aggregate is the right grain because it is the only ordering the money path
has ever needed. ADR-012 already reasoned this out for provider callbacks: two
callbacks about one payment must not overtake each other, and callbacks about two
different payments have no relationship at all. Keying by `merchantId` would
serialize an entire tenant behind one partition to protect an invariant nobody
has, and would put a large merchant's whole event stream on one consumer thread.

**Nothing may depend on cross-key ordering.** That was already true in-process —
the relay publishes oldest-first but consumers are independent per handler — and
it is now enforced by the transport.

The key is Kafka's own slot on the record, not a field in the JSON body. Writing
it in both places would let the two disagree; a consumer that wants it reads
`aggregateId`.

### 5. Versioning: additive within a version, a new version for anything else

`eventVersion` is per `eventType`, not global — `payment.succeeded` v1 and
`order.created` v3 coexist, as the per-capability `*_VERSION` constants in the
producing services already do.

1. **Within a version, changes are additive only.** A new optional key may appear
   in `payload`; an existing key may never be removed, renamed, or change type or
   meaning. Consumers ignore what they do not recognize
   (`FAIL_ON_UNKNOWN_PROPERTIES` is off and `payload` is an open map), so a
   producer ships an addition without a coordinated deploy. `KafkaEventRoundTrip
   Test.anEnvelopeWithFieldsThisVersionDoesNotKnowStillParses` asserts this rather
   than assuming it.
2. **Anything else is a new version.** Both versions are produced during a
   transition long enough for every consumer to move; then the old one is retired.
   A consumer that does not recognize a version must throw — redelivery applies,
   and the event waits — rather than guess at it.

### 6. The publish blocks on the broker's acknowledgement

`KafkaTemplate.send` returns as soon as the record is in the producer's local
buffer, long before any broker has it. `KafkaEventPublisher.publish` waits for
the acknowledgement and throws if it does not come, because the relay's contract
is already "throw and I will retry you" (ADR-025).

This is not a performance oversight; it is the governing invariant. A relay that
treated the buffered return as success would stamp `published_at` on an event
that a crash then discards — an event committed to the database and lost on the
way out.

`acks=all` and `enable.idempotence=true` are set explicitly in
`application.yaml`. `acks=all` means an acknowledged record survives the loss of
the leader that acknowledged it (with three brokers; with one it is identical to
`acks=1`, which is why it is written for the target). Idempotence de-duplicates
the *producer's own* retries by sequence number. **Neither is exactly-once and
neither replaces the consumer inbox** — ADR-016 refused exactly-once and that
refusal stands. They only stop the producer being a second, avoidable source of
duplicates on top of the unavoidable one.

`auto-offset-reset` is `earliest`, not Kafka's default of `latest`. A consumer
group with no committed offset — a new service, a renamed group, a restored
cluster — must start at the beginning of the topic. Starting at "now" silently
skips every event published before the group existed, which on this system means
silently skipping committed money movement. Replay duplicates are safe (the
inbox); a gap is not.

## What is deliberately NOT built

- **No producer, no consumer, no listener.** The relay is untouched and still
  dispatches in-process. `KafkaEventPublisher` is wired as a bean and has no
  caller. The plan's PR table describes the Kafka sink as landing here "behind a
  flag"; its own per-PR section says "the producing/consuming primitives only; no
  capability uses them yet", and this PR follows the section. A flag with nothing
  behind it is a flag that has never been on.
- **No schema registry, no Avro/Protobuf.** JSON serialized by the application's
  own `ObjectMapper`, so the wire format is Jackson under the configuration this
  app already has rather than a second mapper inside a Kafka serializer. A
  registry buys enforcement of §5, which is worth having when the rule has been
  broken once; today it would be a service to run in order to police a contract
  with one producer.
- **No declared topics or partition counts.** Topics are auto-created on first
  publish with one partition. `docker-compose.yml` carries a `ponytail:` note
  naming the ceiling: when a topic needs real partitions, declare it and turn
  auto-creation off, rather than raising a default.
- **No dead-letter topic.** The outbox already has a retry budget and a dead
  letter (ADR-025) on the producing side, and an undelivered event stays in the
  outbox rather than being lost. Consumer-side DLQs belong with consumers, in PR 2.
- **No `spring-kafka-test`, no `@EmbeddedKafka`.** Testcontainers runs the same
  broker image `docker-compose.yml` runs, which is the project's standing
  position on integration tests (ADR-005).

## Consequences

- **The app still starts, and the whole suite still passes, with no broker
  anywhere.** A `KafkaTemplate` opens no connection until something sends, and
  nothing sends. This is the rollback: it is the current state.
- **Only one test starts a broker.** The `KafkaContainer` is declared inside
  `KafkaEventRoundTripTest`, not in the shared `TestcontainersConfiguration`,
  where it would have started a Kafka for every context in the suite to serve one
  test.
- **A new operational dependency exists from PR 2 onward.** From the moment the
  relay publishes, a broker being down means events accumulate in `outbox_events`
  and the backlog health indicator (ADR-025) is the thing that says so. That is
  the correct failure — nothing is lost, delivery is delayed — but it is a new
  way to be paged.
- **The topic naming rule is now load-bearing.** Renaming an event type's domain
  prefix moves its topic, which strands consumers on the old one. Event types were
  already immutable in practice (they are matched on by `EventHandler.eventType()`
  and stored in `processed_events`); this makes that explicit.

## Identifiers

No new identifier type. `EventId` (`evt_`, ADR-003) is already the wire-level
deduplication key and is what a consumer's inbox row is keyed on.
