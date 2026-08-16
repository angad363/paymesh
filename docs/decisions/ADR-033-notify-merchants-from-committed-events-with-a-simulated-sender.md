# ADR-033 — Notify merchants from committed events, with a simulated sender

_Status: accepted. Phase 2, PR 5. Migration V33._

## Context

Phase 2's plan (`docs/phase-2-plan.md` PR 5) and SDD §19.1 call for a
Notification capability: tell a merchant when something happened on their
account — a payment succeeded, a payment failed, a refund was processed.

It is the smallest capability in Phase 2 and the third pure event consumer,
after Webhook and the Ledger. Everything it needs already flows: the outbox
relay, the in-process dispatcher, the `processed_events` inbox and the retry
budget (ADR-016, ADR-025) already carry `payment.succeeded`, `payment.failed`
and `refund.succeeded` to an empty handler list. Notification plugs in as an
`EventHandler` per type and modifies no existing capability.

PayMesh moves no real money and sends no real mail, so the "sender" is
simulated. The design question is not "how do we send email" — it is "what
shape faithfully models a notification platform without building machinery for
channels, templates and providers that do not exist yet".

## Decision

**One table, `notifications` (V33).** A row per (merchant, source event):
who it is for, which event produced it, the rendered subject and body, a
`PENDING → SENT | FAILED` lifecycle, and an `attempt_count`. `source_event_id`
is unique, which is what makes the handler idempotent — a redelivered outbox
event finds the row and does nothing, the same natural-key trick Webhook uses
(`uq_webhook_events_source`).

**Record on the event, send on a timer.** The `EventHandler` runs inside the
dispatcher's transaction and only ever writes one PENDING row. A scheduled
`NotificationDispatcher` — off under `dev` like every other timer here — claims
PENDING rows with `FOR UPDATE SKIP LOCKED`, hands each to the sender, and marks
it SENT. Sending is kept out of the event transaction for the same reason
Webhook keeps its POST out of it: a send is I/O, and a notification failing must
never roll back the payment that triggered it. This is the reviewed shape in
this codebase (Webhook, the simulator, Settlement all record-then-send), reused.

**The sender is a seam.** `NotificationSender` is an interface with one
implementation, `SimulatedNotificationSender`, which logs and reports success.
A real email/SMS provider, or a failure profile, plugs in here without touching
the dispatcher. Because the simulated sender always succeeds, `FAILED` and
`attempt_count > 0` are reachable in production only once a sender that can fail
is installed; the dispatcher's retry/fail logic is exercised in tests by
injecting a sender that throws — the same way the simulator's failure profiles
reach the states a happy-path sender never would.

**`GET /internal/v1/notifications/{id}`**, platform-admin only
(`requirePlatformAdmin()`), for support diagnosis. This is the only read
surface; a merchant-facing list is not built because nothing asks for one yet.

## What is deliberately NOT built

Two of the three tables the plan named are not built, each with direct
precedent in this codebase for cutting exactly this kind of table:

- **No `notification_templates` table.** A template is static per-event content
  that changes with a deploy. Risk made its rules code, not a table, for the
  same reason (ADR-030: "add a stored expression when a non-engineer needs to
  change a rule without a deploy"). `NotificationTemplates` is a class.
  **Add the table when a non-engineer needs to edit copy without a deploy** —
  that is the only thing it buys.

- **No `delivery_attempts` table.** Webhook deliberately skipped
  `webhook_delivery_attempts` (ADR-028 §3: "a log wearing a table's clothes")
  in favour of counters on the delivery row. Same call: `attempt_count` and
  `last_error` on the notification row answer what a support engineer asks.
  **Add the table if per-attempt forensics (per-attempt timestamps, provider
  response bodies) are ever needed** — it attaches without touching this table.

- **No exponential backoff, no `next_attempt_at`.** A simulated sender does not
  fail, so it does not need to wait before retrying. Failed attempts (from a
  real sender) are retried on the next ordinary pass until `attempt_count`
  reaches the budget, then `FAILED`. **Add a backoff schedule when a real
  sender makes transient failure common** — Webhook's `next_attempt_at` is the
  pattern to copy.

- **No channel or recipient column.** Every notification today is the same
  channel to the same party (the merchant, identified by `merchant_id`). A
  `channel` column with one value and a `recipient` PayMesh cannot populate
  without a cross-module `MerchantLookup` would both be scaffolding for a second
  channel that does not exist. **Add both when a second channel (SMS) or a real
  address is needed**; Notification stays a leaf until then.

## Consequences

- Notification is a **leaf**: it imports nothing from any capability and reads
  events as `Map<String, Object>`, exactly like the Ledger and Webhook.
  `ModuleBoundaryTest` asserts it with an empty allowlist.
- `nfn_` joins the prefixed-id set (ADR-003).
- The invariant to protect, and the whole reason this is an event consumer
  rather than a step in any transaction: **a notification failing never rolls
  back a payment.**
