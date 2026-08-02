-- ============================================================================
--  V14__create_processed_events.sql
--  Creates processed_events: the INBOX. Which consumer has already applied
--  which event. SDD section 22.4, and section 15.5's requirement that a ledger
--  posting be safe to redeliver. ADR-016.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entity, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  THE INVARIANT THIS TABLE EXISTS TO PROTECT: delivery is AT-LEAST-ONCE and
--  can never be exactly-once, so the same event WILL arrive twice, and applying
--  it twice must be impossible rather than unlikely. V7 said this at the point
--  event_id was defined -- "event_id below is the value a consumer dedups on
--  (SDD 22.4's inbox)" -- and this is that table finally existing.
--
--  Why at-least-once is not a defect to remove: the relay stamps
--  outbox_events.published_at in a SEPARATE transaction from the one a handler
--  commits in. Between those two commits the process can die, and on restart the
--  relay sees an unpublished row and delivers it again. Merging the two
--  transactions would not fix it, only move it -- a consumer that will one day
--  live in another service cannot share a transaction with the relay at all.
--  So the duplicate is designed for, here, rather than argued away.
--
--  A ROW HERE IS WRITTEN IN THE SAME TRANSACTION AS THE STATE CHANGE IT
--  AUTHORIZES. That is the mirror image of the outbox rule (ADR-010): out there,
--  the state change and the event announcing it commit together; in here, the
--  state change and the record of having consumed the event commit together. If
--  the two could commit separately, a crash between them would either lose the
--  work (dedup row committed, change rolled back -- and the event is never
--  redelivered because this table says it was handled) or double-apply it
--  (change committed, dedup row rolled back). Both are unrecoverable; the single
--  transaction is what makes neither representable.
-- ============================================================================

CREATE TABLE processed_events (
    -- WHICH CONSUMER, and it leads the primary key on purpose. One event is
    -- delivered to EVERY subscribed consumer, and each must dedup independently:
    -- Order consuming payment.succeeded must not suppress the Ledger's
    -- consumption of the same event. A key of event_id alone would mean the
    -- first consumer to run silently starves every other one -- and the failure
    -- would look like "the Ledger never posts", days later, with nothing in any
    -- log to say why.
    --
    -- THE VALUE IS A STABLE NAME, NOT A CLASS NAME. Renaming it re-opens the
    -- whole backlog to that consumer, which would replay every event it has ever
    -- handled. It is chosen once, in the handler, and left alone. VARCHAR(100) is
    -- generous for "order.payment-succeeded" and bounded so the key stays small.
    consumer_name VARCHAR(100)             NOT NULL,

    -- The event's own public identifier, "evt_" + UUID (ADR-003), copied from
    -- outbox_events.event_id. Always exactly 40 chars, so VARCHAR(40) fits it
    -- exactly and matches the producing column's width.
    event_id      VARCHAR(40)              NOT NULL,

    -- What kind of event it was. NOT part of the key and NOT used by any query:
    -- it is here so that a human reading this table during an incident can tell
    -- what a consumer has been chewing on without joining back to a table that
    -- may, once the transport is a broker, live in another database entirely.
    -- One column of denormalized text is a cheap price for an inbox that is
    -- readable on its own.
    event_type    VARCHAR(80)              NOT NULL,

    -- When THIS consumer applied it -- not when the event happened, and not when
    -- the relay published it. Those two live on outbox_events and mean different
    -- things. TIMESTAMP WITH TIME ZONE so the value is an unambiguous absolute
    -- point in time (stored as UTC), matching java.time.Instant. Supplied by the
    -- app's Clock, so no DB DEFAULT: the application stays the source of time,
    -- which is what lets a test pin it.
    --
    -- It is also the column a future retention job sweeps on. Nothing prunes this
    -- table today; it grows with the event log, which is the correct trade while
    -- "have I seen this" must be answerable for every event that exists.
    processed_at  TIMESTAMP WITH TIME ZONE NOT NULL,

    -- THE PRIMARY KEY IS THE CONCURRENCY CONTROL, NOT AN ACCESS PATH. The claim
    -- is INSERT ... ON CONFLICT DO NOTHING and the ROW COUNT is the answer: one
    -- row inserted means this consumer has not seen the event and must handle it,
    -- zero means it already has. There is no read-then-write window, because
    -- there is no read -- the database picks the single winner, exactly as
    -- pk_idempotency_records does for public writes (ADR-009, V4).
    --
    -- A concurrent duplicate BLOCKS on this index entry until the first
    -- transaction resolves. If that one commits, the second reads zero rows and
    -- correctly does nothing; if it rolls back, the second's insert succeeds and
    -- it correctly applies the event. Neither outcome needs application logic.
    --
    -- There is no surrogate id competing with it, for the same reason
    -- idempotency_records has none: nothing outside the server ever names one of
    -- these rows, and ADR-003 governs identifiers that appear in an API.
    CONSTRAINT pk_processed_events PRIMARY KEY (consumer_name, event_id)
);

-- ============================================================================
--  TWO THINGS THIS TABLE DELIBERATELY DOES NOT HAVE.
-- ============================================================================
--
-- NO merchant_id, AND THAT IS NOT A HOLE IN THE TENANT RULE. Every merchant-
-- OWNED table carries the tenant and every query scopes by it; this table is
-- owned by a consumer, not a merchant. The dedup identity is the event,
-- platform-wide -- V7 says exactly why at the point event_id is defined: "an
-- event id that repeated across tenants would be processed once per tenant by
-- any consumer that is not itself tenant-scoped." Adding the column would put
-- data here that no query reads; adding it to the KEY would be actively wrong,
-- because it would let one event be applied once per merchant. The tenant
-- travels in the event envelope, and every write a handler makes is scoped by
-- it -- which is where the isolation is enforced and where it belongs.
--
-- NO FOREIGN KEY TO outbox_events. This is a CONSUMER's table, and it must
-- outlive the assumption that the producer shares its database. The moment the
-- transport is a broker (SDD 22.2), this inbox holds ids for events another
-- service's outbox produced, and the constraint would have to be migrated away
-- at precisely the moment the system is least able to afford a schema change.
-- SDD 22.4 defines the inbox on the consuming side for this reason. The event id
-- is opaque to this table by design: it compares it, it never resolves it.
--
-- And no secondary index. Every read and every write is
-- "WHERE consumer_name = ? AND event_id = ?", which the primary key answers
-- exactly. An index no query uses is a write cost on the path every delivered
-- event now takes.
