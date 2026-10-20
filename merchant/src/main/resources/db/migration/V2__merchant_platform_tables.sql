-- ============================================================================
--  V2__merchant_platform_tables.sql
--  This deployable's own copy of the platform tables the monolith kept in its
--  shared `platform` schema (ADR-038): inbox, idempotency store, outbox
--  (ADR-046, ADR-042/043/045 recipe).
--
--  NEW PHYSICAL TABLES, NOT MOVED ONES: runs on both a fresh and the adopted
--  shared dev database. `merchant_svc` has no grant on `platform` (ADR-038).
--    - processed_events / idempotency_records: platform parity (no consumer and
--      no idempotent route today).
--    - outbox_events: merchant.registered/activated/suspended/closed and
--      merchant.status_changed.audited are REAL events here (ADR-046 section 3).
--  No FK to `merchants` (it is a sibling table, not a platform dependency); the
--  identifier-format CHECKs are kept. outbox_events.merchant_id is NULLABLE,
--  matching the monolith's V40.
-- ============================================================================


-- ----------------------------------------------------------------------------
--  processed_events -- the inbox (SDD 22.4, ADR-016)
-- ----------------------------------------------------------------------------
CREATE TABLE processed_events (
    consumer_name VARCHAR(100)             NOT NULL,
    event_id      VARCHAR(40)              NOT NULL,
    event_type    VARCHAR(80)              NOT NULL,
    processed_at  TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_processed_events PRIMARY KEY (consumer_name, event_id),

    CONSTRAINT ck_processed_events_event_id_format
        CHECK (is_prefixed_id(event_id, 'evt_'))
);


-- ----------------------------------------------------------------------------
--  idempotency_records -- durable memory of attempted public writes (ADR-009)
-- ----------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    merchant_id     VARCHAR(40)              NOT NULL,
    endpoint        VARCHAR(200)             NOT NULL,
    idempotency_key VARCHAR(255)             NOT NULL,
    request_hash    CHAR(64)                 NOT NULL,
    status          VARCHAR(20)              NOT NULL,
    response_status SMALLINT,
    response_body   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (merchant_id, endpoint, idempotency_key),

    CONSTRAINT ck_idempotency_records_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),

    CONSTRAINT ck_idempotency_records_response_pairing CHECK (
        (status = 'IN_PROGRESS' AND response_status IS NULL     AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND response_status IS NOT NULL AND completed_at IS NOT NULL)
    ),

    CONSTRAINT ck_idempotency_records_response_status CHECK (
        response_status IS NULL OR response_status BETWEEN 100 AND 599
    ),

    CONSTRAINT ck_idempotency_records_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);


-- ----------------------------------------------------------------------------
--  outbox_events -- this module's own outbox (ADR-010, ADR-016, ADR-025,
--  ADR-037, ADR-043)
-- ----------------------------------------------------------------------------
CREATE TABLE outbox_events (
    event_id           VARCHAR(40)              NOT NULL,
    -- Nullable: see the file header (ADR-043). Every event about a real
    -- merchant still carries it.
    merchant_id        VARCHAR(40),
    aggregate_type      VARCHAR(40)              NOT NULL,
    aggregate_id        VARCHAR(40)              NOT NULL,
    event_type          VARCHAR(80)              NOT NULL,
    event_version       INTEGER                  NOT NULL,
    payload             JSONB                    NOT NULL,
    occurred_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at        TIMESTAMP WITH TIME ZONE,

    -- ADR-025: the relay's memory of failed delivery attempts.
    attempt_count       INTEGER                  NOT NULL DEFAULT 0,
    last_attempt_at     TIMESTAMP WITH TIME ZONE,
    last_error          TEXT,
    dead_lettered_at    TIMESTAMP WITH TIME ZONE,

    -- ADR-037: the dual-path relay's second, independent sink.
    kafka_published_at  TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_outbox_events PRIMARY KEY (event_id),

    CONSTRAINT ck_outbox_events_version CHECK (event_version > 0),

    CONSTRAINT ck_outbox_events_attempt_count CHECK (attempt_count >= 0),

    CONSTRAINT ck_outbox_events_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_outbox_events_event_id_format
        CHECK (is_prefixed_id(event_id, 'evt_'))
);

CREATE INDEX idx_outbox_events_unpublished ON outbox_events (occurred_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;

CREATE INDEX idx_outbox_events_dead_lettered ON outbox_events (dead_lettered_at)
    WHERE dead_lettered_at IS NOT NULL;

CREATE INDEX idx_outbox_events_unpublished_to_kafka ON outbox_events (occurred_at)
    WHERE kafka_published_at IS NULL AND dead_lettered_at IS NULL;
