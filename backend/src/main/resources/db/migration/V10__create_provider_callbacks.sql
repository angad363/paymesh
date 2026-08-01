-- ============================================================================
--  V10__create_provider_callbacks.sql
--  Creates provider_callbacks. SDD section 12.6/21.4, design section 4.1.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entity, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  A provider callback is the only thing that can move a payment past
--  PROCESSING. Callbacks arrive DUPLICATED, OUT OF ORDER and LATE, and this
--  table is the first of the three mechanisms that answer them (ADR-012):
--
--    duplicated   -> this table's primary key, with the row inserted inside the
--                    same transaction as the state change, so a duplicate rolls
--                    the whole thing back and nothing happened.
--    out of order -> the intent's state machine plus the monotonic
--                    payment_attempts.last_provider_event_at that V9 declared.
--    late         -> out of scope; reconciliation needs the Provider Simulator.
--
--  Every row is kept, including the ones that changed nothing. A refused event
--  that left no trace would be re-judged from scratch on every re-delivery, and
--  an IGNORED_TERMINAL row is the only record of a genuine PayMesh/provider
--  divergence -- the case where a merchant cancelled a 3DS challenge the
--  customer then completed. Reconciliation will look for exactly these.
-- ============================================================================

CREATE TABLE provider_callbacks (
    -- Which provider spoke. 'SIMULATOR' for now, matching payment_attempts.
    provider           VARCHAR(50)              NOT NULL,

    -- The PROVIDER's identifier for this delivery, not ours. Two deliveries
    -- carrying the same value are the same event by definition, which is what
    -- makes the primary key below a deduplication rule rather than a formality.
    external_event_id  VARCHAR(120)             NOT NULL,

    -- Owning tenant, DERIVED from the intent the callback names and never
    -- supplied by the caller. It is on the row because the composite foreign key
    -- below needs it and because every operational query scopes by it.
    merchant_id        VARCHAR(40)              NOT NULL,

    -- The intent this callback is about.
    payment_intent_id  VARCHAR(40)              NOT NULL,

    -- SHA-256 of the RAW request body, hex, computed by the signature filter
    -- before the body is parsed. Two deliveries sharing an external_event_id but
    -- disagreeing on content are findable by comparing this against the stored
    -- payload's hash; the first delivery is the one that took effect.
    payload_hash       CHAR(64)                 NOT NULL,

    -- The body, REDACTED BY ALLOWLIST before it lands here (SDD 12.6, design
    -- section 4.5). Only the fields the design names survive, and any URL keeps
    -- its origin and path while its query string is dropped -- a 3DS action URL
    -- routinely carries a challenge token, and this row is a durable audit
    -- record. A denylist would grow a hole the first time a provider adds a
    -- field, so the rule is stated the other way round.
    payload            JSONB                    NOT NULL,

    -- What this platform DID about the event, which is not what the event said.
    -- APPLIED moved the intent; the two IGNORED_* outcomes did not, and they are
    -- stored precisely because they did not.
    outcome            VARCHAR(32)              NOT NULL,

    -- The PROVIDER's timestamp for the event, and the value the monotonic
    -- ordering guard compares. Not the same clock as received_at, and the
    -- difference between them is how late a delivery was.
    occurred_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    -- When this platform took delivery, and when it finished judging it.
    received_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at       TIMESTAMP WITH TIME ZONE NOT NULL,

    -- ========================================================================
    --  DELIBERATELY NOT MERCHANT-LEADING. DO NOT "FIX" THIS.
    --
    --  Every other uniqueness rule in PayMesh leads with merchant_id, because
    --  every other one scopes data a merchant supplied. This one does not:
    --  external_event_id is the PROVIDER's id, it is provider-global, and the
    --  merchant here is DERIVED from the intent the callback names rather than
    --  supplied by the caller.
    --
    --  Adding merchant_id to this key would let a single provider event be
    --  processed once per merchant it can be resolved against -- which is the
    --  exact duplicate this constraint exists to prevent, and a duplicate that
    --  moves money. An implementer "fixing" this to match the house style
    --  reopens the hole.
    --
    --  THE ROW IS INSERTED INSIDE THE SAME TRANSACTION AS THE STATE CHANGE, and
    --  that is not an implementation detail either. A duplicate delivery loses
    --  on this key, the transaction rolls back, and nothing happened: no
    --  transition, no payment_state_history row, no outbox event. A concurrent
    --  duplicate does not fail immediately -- PostgreSQL blocks the second
    --  inserter on the index entry until the first transaction resolves. If the
    --  first commits, the second gets its violation and correctly no-ops. If the
    --  first ROLLS BACK, the second's insert succeeds and it correctly applies
    --  the event, which is why an in-transaction insert cannot swallow an event
    --  that was never actually processed.
    --
    --  Contrast the idempotency layer (ADR-009), which commits its record BEFORE
    --  the handler runs. That is right there and wrong here: an orphaned
    --  idempotency record is recoverable with a new key, whereas an orphaned
    --  callback row would swallow the provider's event permanently and leave the
    --  payment stuck in PROCESSING with no way to replay it.
    -- ========================================================================
    CONSTRAINT pk_provider_callbacks PRIMARY KEY (provider, external_event_id),

    -- COMPOSITE, for the reason V8 and V9 state: a foreign key on
    -- payment_intent_id alone would let a callback be recorded against one
    -- merchant while pointing at another merchant's intent. It references
    -- uq_payment_intents_merchant_intent, which V8 created for this.
    --
    -- Both columns are NOT NULL because a callback naming an intent this
    -- platform does not know is REJECTED AND STORED NOWHERE. There is nothing to
    -- deduplicate for an event that had no effect, and storing rows keyed on a
    -- caller-chosen external_event_id for intents that do not exist is unbounded
    -- write amplification on an endpoint reachable with one shared secret. The
    -- endpoint answers 404 there, which is a deliberate request to RETRY -- the
    -- likeliest cause is a callback overtaking the transaction that created the
    -- intent -- and it is the only non-2xx the endpoint has.
    CONSTRAINT fk_provider_callbacks_intent FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, payment_intent_id),

    -- The three outcomes a STORED row can carry. DUPLICATE is deliberately
    -- absent: it is an answer to the caller, not a row -- by the time it is
    -- known, this transaction is rolling back and the row that already exists is
    -- the record.
    CONSTRAINT ck_provider_callbacks_outcome CHECK (outcome IN (
        'APPLIED',
        'IGNORED_STALE',
        'IGNORED_TERMINAL'
    )),

    -- A row cannot be judged before it arrived.
    CONSTRAINT ck_provider_callbacks_processed_at CHECK (processed_at >= received_at)
);

-- "What has this provider said about this intent, most recent first?" -- the
-- question reconciliation and a support investigation both start from, and the
-- only access pattern the table has beyond the primary key.
CREATE INDEX idx_provider_callbacks_intent
    ON provider_callbacks (merchant_id, payment_intent_id, received_at DESC);
