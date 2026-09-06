-- ============================================================================
--  V2__webhook_platform_tables.sql
--  This deployable's own copy of the three platform tables the monolith kept
--  in its shared `platform` schema (ADR-038): the inbox, the idempotency store,
--  and the outbox. ADR-042 section 2.
--
--  THESE ARE NEW PHYSICAL TABLES, NOT MOVED ONES -- unlike V1, this runs on
--  BOTH a fresh database and the adopted shared dev database, because nothing
--  in the `webhook` schema created these before this module existed. The
--  monolith's `platform.processed_events` / `.idempotency_records` /
--  `.outbox_events` remain exactly as they are; this module never reads them
--  and never will (ADR-038 fencing: `webhook_svc` has no grant on `platform`).
--
--  WHY WEBHOOK NEEDS ITS OWN COPY OF EACH:
--    - processed_events: a Kafka consumer with no inbox is not idempotent, and
--      the platform schema's copy is out of reach across the process boundary.
--    - idempotency_records: the `/deliveries/{id}/replay` route runs through
--      IdempotencyFilter.
--    - outbox_events: webhook now PRODUCES one event type of its own
--      (webhook.secret_rotated.audited, ADR-042 section 4), so it needs a
--      durable outbox and the relay that reads it.
--
--  Byte-for-byte the monolith's originals (V4 idempotency, V7 outbox + V21's
--  ADR-025 delivery-attempt columns + V37's kafka_published_at, and
--  processed_events' creating migration, V14) MINUS each table's FK to
--  `merchants`. That FK was explicitly kept in the monolith's `platform` copy
--  only because "platform splits per-service at extraction... and the FK goes
--  then, with the tables" (V39's own comment, naming this exact day). This is
--  that split, for webhook's tables: `webhook_svc` has no `merchants` table in
--  its reach to reference (ADR-038 fencing), so the FK cannot be carried over,
--  and V39 already said it would not be. The identifier-format CHECK on each
--  merchant_id column (V26) is kept.
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
--  ADR-037)
-- ----------------------------------------------------------------------------
CREATE TABLE outbox_events (
    event_id           VARCHAR(40)              NOT NULL,
    merchant_id        VARCHAR(40)              NOT NULL,
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
