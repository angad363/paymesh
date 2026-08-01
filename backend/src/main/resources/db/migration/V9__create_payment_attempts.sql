-- ============================================================================
--  V9__create_payment_attempts.sql
--  Creates payment_attempts. SDD section 12.4/12.5, design section 3.1.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entity, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  An intent says what is being collected. An ATTEMPT is one try at collecting
--  it: one conversation with one provider, with its own outcome. The two are
--  separate rows because the relationship is one-to-many -- a customer who
--  abandons a 3DS challenge and confirms again produces a second attempt against
--  the same intent, and squashing them into the intent would destroy the record
--  of the first.
--
--  In THIS migration's PR only one attempt is reachable and its status is always
--  PROCESSING: confirm creates the row and stops, because there is no provider to
--  call. The remaining statuses, provider_reference and last_provider_event_at
--  are declared now and moved by the provider-callback PR -- the same precedent
--  V5 set for orders and V8 for payment intents, so that no later PR needs a
--  migration to widen a column.
-- ============================================================================

CREATE TABLE payment_attempts (
    -- Opaque, application-generated identifier "pat_" + UUID (ADR-003, and the
    -- prefix SDD 12.4 already uses). VARCHAR(40) matches every other id column.
    payment_attempt_id      VARCHAR(40)              NOT NULL,

    -- Owning tenant. Carried on the row rather than reached through the intent,
    -- because the composite foreign key below needs it and because every query
    -- in the application scopes by it.
    merchant_id             VARCHAR(40)              NOT NULL,

    -- The intent this attempt is trying to collect.
    payment_intent_id       VARCHAR(40)              NOT NULL,

    -- 1-based, per intent. Always 1 in the PR that adds this table: a second
    -- attempt needs REQUIRES_ACTION, and nothing reaches it yet. The column and
    -- uq_payment_attempts_intent_number below exist so the PR that does needs no
    -- migration -- and so that two concurrent confirms cannot both create
    -- "the first attempt".
    attempt_number          INTEGER                  NOT NULL,

    -- Which provider was asked. 'SIMULATOR' for now, and the Provider Simulator
    -- does not exist yet either -- the value names the seam rather than a running
    -- system, so that the row an eventual real provider writes is the same shape.
    provider                VARCHAR(50)              NOT NULL,

    -- The provider's own identifier for this attempt. NULL until it answers,
    -- which in this PR is forever: there is no outbound call at all.
    provider_reference      VARCHAR(120),

    -- The attempt's own lifecycle, which is NOT the intent's. An intent can
    -- outlive a failed attempt and be confirmed again; the attempt's status is
    -- frozen at whatever the provider last said about that try.
    status                  VARCHAR(32)              NOT NULL,

    -- Copied from the intent at confirm time and never recomputed. An attempt
    -- records what was actually asked for, so if the intent's amount could ever
    -- change (it cannot, by V8's design) the attempt would still say what this
    -- try was worth.
    amount_minor            BIGINT                   NOT NULL,
    currency                CHAR(3)                  NOT NULL,

    -- The provider's reason, after redaction. Written by the callback PR.
    failure_code            VARCHAR(60),
    failure_message         VARCHAR(500),

    -- THE OUT-OF-ORDER GUARD, declared now and enforced by the callback PR.
    -- Provider callbacks arrive duplicated, out of order and late; the intent's
    -- state machine refuses most backwards moves on its own, but it contains a
    -- cycle (PROCESSING -> REQUIRES_ACTION -> PROCESSING) in which a stale event
    -- is a LEGAL transition. A monotonic clock per attempt is what refuses it.
    last_provider_event_at  TIMESTAMP WITH TIME ZONE,

    -- What was sent and what came back, both REDACTED before they land here.
    -- Raw instrument data is never stored (SDD 12.6). In this PR request_payload
    -- carries only the confirm call's returnUrl and device, and response_payload
    -- stays NULL because nothing answers.
    request_payload         JSONB,
    response_payload        JSONB,

    -- Optimistic lock (SDD 23.3), for the callback PR's updates. Declared NOT
    -- NULL from the start so no row can exist without one.
    version                 INTEGER                  NOT NULL,

    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_payment_attempts PRIMARY KEY (payment_attempt_id),

    -- Prerequisite for the composite foreign keys later tables will point at
    -- this one with, exactly as uq_payment_intents_merchant_intent is for this
    -- table. Redundant with the primary key, and that redundancy is the price of
    -- a tenant-safe reference.
    CONSTRAINT uq_payment_attempts_merchant_attempt UNIQUE (merchant_id, payment_attempt_id),

    -- COMPOSITE, AND THAT IS THE WHOLE POINT. A foreign key on payment_intent_id
    -- alone would let one merchant's attempt hang off another merchant's intent,
    -- leaving only an advisory application check between them. It references
    -- uq_payment_intents_merchant_intent, which V8 created for this.
    CONSTRAINT fk_payment_attempts_intent FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, payment_intent_id),

    -- ONE ATTEMPT PER NUMBER PER INTENT, ENFORCED BY THE DATABASE (SDD 12.5).
    --
    -- The application computes the next number by counting the attempts an intent
    -- already has, and that count is a check, not a lock: two concurrent confirms
    -- both read zero and both try to write attempt 1. This constraint is what
    -- makes the second one lose, and it is the same argument
    -- uq_payment_intents_live_per_order rests on. Without it a double-clicked
    -- confirm would open two collections against one intent.
    --
    -- Not merchant-leading, and it does not need to be: payment_intent_id is
    -- globally unique, so scoping the pair by merchant would only weaken it.
    CONSTRAINT uq_payment_attempts_intent_number UNIQUE (payment_intent_id, attempt_number),

    CONSTRAINT ck_payment_attempts_number CHECK (attempt_number > 0),

    CONSTRAINT ck_payment_attempts_amount CHECK (amount_minor > 0),

    CONSTRAINT ck_payment_attempts_currency CHECK (currency ~ '^[A-Z]{3}$'),

    -- The attempt's five states, and no more. An attempt is created at
    -- PROCESSING and only a provider moves it, so the intent's pre-provider
    -- states (REQUIRES_PAYMENT_METHOD, REQUIRES_CONFIRMATION) and its
    -- merchant-driven CANCELLED are deliberately absent: an attempt in flight is
    -- not cancelled by the merchant giving up locally, and saying otherwise in
    -- this column would be a claim about the provider that PayMesh cannot make.
    CONSTRAINT ck_payment_attempts_status CHECK (status IN (
        'PROCESSING',
        'REQUIRES_ACTION',
        'AUTHORIZED',
        'SUCCEEDED',
        'FAILED'
    ))
);

-- The join key a callback arrives on when it names a provider reference rather
-- than an intent id, which is the normal case for a real provider.
--
-- PARTIAL, because provider_reference is NULL for every attempt that has not
-- been answered yet and a plain unique index would collapse all of them into one
-- allowed row -- NULLs are distinct in a unique index in PostgreSQL, so it would
-- in fact permit them, but the partial form states the intent and keeps the
-- index off rows that can never be looked up through it.
--
-- Provider-scoped and NOT merchant-leading, for the same reason
-- provider_callbacks will not be: a provider's reference is provider-global, and
-- the merchant is derived from the attempt it names rather than supplied by the
-- caller. Adding merchant_id here would let one provider reference be claimed
-- once per merchant, which is the exact collision this index exists to stop.
CREATE UNIQUE INDEX uq_payment_attempts_provider_reference
    ON payment_attempts (provider, provider_reference)
    WHERE provider_reference IS NOT NULL;

-- "What has this intent tried, most recent first?" -- the only access pattern
-- the table has, and the one the next attempt number is counted from.
CREATE INDEX idx_payment_attempts_intent
    ON payment_attempts (merchant_id, payment_intent_id, attempt_number DESC);
