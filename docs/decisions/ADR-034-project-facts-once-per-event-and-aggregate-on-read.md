# ADR-034 — Project one fact per event, aggregate on read

_Status: accepted. Phase 2, PR 6. Migrations V34–V35._

## Context

Phase 2's plan (`docs/phase-2-plan.md` PR 6) and SDD §19.2 call for a Reporting
capability: merchant-scoped, denormalized projections built from domain events,
with four endpoints — a payment summary, a settlement summary, an asynchronous
CSV export, and the read for that export. The SDD adds one requirement that
shapes everything: the UI must display an as-of timestamp or a delayed-data
signal when consumers lag.

Reporting is the last of the three pure event consumers, after Webhook and
Notification. Everything it needs already flows: the outbox relay, the
in-process dispatcher, the `processed_events` inbox and the retry budget
(ADR-016, ADR-025). It plugs in as an `EventHandler` per type and modifies no
existing capability. It is sequenced last of the three deliberately, so that
settlement reporting lands in the same pass rather than needing a second one —
by now `settlement.batch_cut`, `payout.paid` and `payout.returned` all exist.

The design question is not "how do we build a dashboard". It is "what shape
serves both reports and the export, stays a leaf, and tells the truth about
being eventually consistent".

## Decision

**One append-only fact table, `report_facts` (V34), aggregated on read.** A row
per source event — which merchant, when, what kind of thing, how much, which
currency, about what. Both reports are a `GROUP BY` over it; the CSV export is a
`SELECT`. `source_event_id` is the **primary key**, so a redelivered event is a
refused insert rather than a double-counted payment — the idempotency guard is
the natural key itself, no second column needed.

The rejected alternative was a row per payment intent, mutated as the payment
succeeds and is later refunded. That design has to be correct under concurrent
and out-of-order delivery, and the failure mode of getting it wrong is a
silently understated total, not an error — the exact race
`ApplyRefundSucceededService` takes a row lock to avoid. A row per event never
reads before it writes, so there is nothing to race on. Corrections arrive as
further facts, the way the Ledger corrects itself with reversal transactions
rather than edits (ADR-018).

**Six subscribed types**, pinned in three places that must move together:
`ReportFact.SUBSCRIBED_TYPES`, the handler-bean list in `ReportingConfiguration`,
and `ck_report_facts_event_type`. `order.paid` is deliberately absent — it is
Order's restatement of `payment.succeeded`, so counting both would double every
collection, the same reason Notification omits it.

**`asOf` is the newest `recorded_at`, never the read time.** Reporting stamps
its own clock on each fact as it ingests it, and every report response reports
the maximum. Because delivery is asynchronous, an event committed a moment ago
may still be unpublished in the outbox, so the projection is eventually
consistent by construction. Reporting `now()` would claim currency the
projection does not have; reporting the newest fact means a relay that has
stopped shows up as an `asOf` that stops advancing — which is exactly the
delayed-data signal the SDD asks for. `asOf` is `null` when a merchant has no
facts, an honest "nothing projected yet" rather than a fresh-looking empty
report.

**Everything is per currency, and nothing sums across.** Money in this codebase
is always integer minor units plus an explicit currency, and a report is the
easiest place in a payment platform to quietly break that rule by adding USD to
EUR. Both summaries return one entry per currency.

**Exports are asynchronous, request-then-generate (V35).** `POST` records a
`PENDING` row and returns a `rex_` id with `202`; a scheduled generator — off
under `dev` like every other timer here — claims rows with `FOR UPDATE SKIP
LOCKED`, renders the CSV and marks them `COMPLETED`. The work is proportional to
a window the caller chooses, so doing it synchronously would let a merchant hold
a request thread for as long as their history is deep. This is the reviewed
record-then-do shape (Notification, Webhook, the simulator), reused.

**The CSV lives in a `TEXT` column, and the export is one shape.** There is no
object storage in this project (`infrastructure/` is empty, SDD §27 is not
started), so a `content` column is the honest option — it will not hold a
million-row export, but it will not hand a merchant a download URL for a bucket
that does not exist either (the same honesty ADR-033 keeps by naming its sender
*simulated*). There is one export type: the merchant's facts in a window, which
is the data behind *both* summaries. A `reportType` discriminator would exist
only to choose between a CSV of numbers the JSON endpoints already return and
this one.

**Four endpoints, and the download is content negotiation.** `GET
/api/v1/reports/payment-summary`, `GET /api/v1/reports/settlements`, `POST
/api/v1/report-exports`, `GET /api/v1/report-exports/{id}`. The last answers
JSON metadata by default and the CSV under `Accept: text/csv` — one resource,
two representations, which is what content negotiation is for and keeps the four
endpoints the plan named. Asking for the CSV before it is rendered is a `409`
(the export exists, the representation does not yet — keep polling), not a `404`.

## What is deliberately NOT built

- **No pre-aggregated daily rollup.** A `GROUP BY` over one merchant's own facts
  is cheap at this size, and a rollup is strictly this-plus-a-cache: it would
  need this table anyway, because the export selects rows. Add it the day a
  report is measurably slow, backed by the partial-index pattern already here —
  not before.

- **No OpenSearch.** SDD §19.2 permits it only if search requirements justify
  it, and they do not yet. PostgreSQL holds the projection.

- **No export retention or expiry.** SDD §19.3's `audit_exports` names an
  expiry; nothing here reads one, and a column nothing reads is a promise
  nothing keeps. Add a sweep when storage pressure is real.

- **No per-fact identifier and no fact read endpoint.** A merchant asks for a
  summary or an export, never for one row, so a fact is keyed by the `evt_`
  that produced it and is not addressable on its own (ADR-003 ids are for things
  a client names).

## Consequences

- A seventh event type is four coordinated edits: an extraction in
  `ReportFactExtractor`, an entry in `ReportFact.SUBSCRIBED_TYPES`, a handler
  bean, and a widened `ck_report_facts_event_type`. Three of the four fail at
  startup if forgotten (the handler constructor refuses a type the extractor
  cannot read); `ReportingConfigurationTest` catches the fourth, an unregistered
  handler.

- An export over a window holding more facts than the row cap is `FAILED` with a
  reason naming the number, not retried forever — the one non-transient failure
  the generator makes terminal, so a request that can never be satisfied does
  not burn a pass every interval.

- Reporting names no other capability's types. The payload is read as a
  `Map<String, Object>` out of the envelope, exactly as a consumer in another
  process would, which is what lets `ModuleBoundaryTest` keep an empty allowlist
  while Reporting consumes six other capabilities' events. It is extractable
  into a service without touching a producer.

- The reports are eventually consistent and say so on every response. A read
  model that lagged silently would be worse than one that admits it — the whole
  reason `asOf` is in the contract rather than a debug field.
