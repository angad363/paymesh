-- ============================================================================
--  V2__risk_platform_tables.sql
--  This deployable's own copy of two of the three platform tables the monolith
--  kept in its shared `platform` schema (ADR-038): the inbox and the outbox.
--  ADR-044 section 2.
--
--  THESE ARE NEW PHYSICAL TABLES, NOT MOVED ONES -- unlike V1, this runs on
--  BOTH a fresh database and the adopted shared dev database, because nothing
--  in the `risk` schema created these before this module existed. The
--  monolith's `platform.processed_events` / `.outbox_events` remain exactly as
--  they are; this module never reads them and never will (ADR-038 fencing:
--  `risk_svc` has no grant on `platform`).
--
--  WHY RISK NEEDS ITS OWN COPY OF EACH:
--    - processed_events: the Kafka consumer of payment.created (feeding
--      payment_intent_ref, V3) is not idempotent without an inbox.
--    - outbox_events: declared for platform parity with every other extracted
--      deployable (ADR-042/043's own instinct) and because
--      PublishOutboxEventsService/EventDispatcher are wired unconditionally --
--      not because Risk produces an event of its own. ADR-030 is explicit that
--      Risk emits nothing ("Risk decides, Payment acts"), so this table is
--      expected to stay empty; a relay running over an empty table costs one
--      idle query per tick.
--
--  idempotency_records IS NOT CREATED HERE, unlike webhook's and engagement's
--  V2 (ADR-044 section 3). Risk's one endpoint,
--  POST /internal/v1/risk-evaluations, is a machine-to-machine synchronous
--  decision call authenticated by a shared key, not a merchant-facing public
--  write behind IdempotencyFilter -- there is no Idempotency-Key header on this
--  route to store against, and the shared idempotency infrastructure was not
--  copied into this deployable's `shared` package for exactly that reason.
--
--  Byte-for-byte the monolith's originals (V7 outbox + V21's ADR-025
--  delivery-attempt columns + V37's kafka_published_at + V40's nullable
--  merchant_id, and processed_events' creating migration, V14) MINUS each
--  table's FK to `merchants`, the same split webhook's and engagement's V2
--  already made: `risk_svc` has no `merchants` table in its reach to
--  reference (ADR-038 fencing). The identifier-format CHECK on each
--  merchant_id column (V29) is kept. outbox_events.merchant_id is nullable
--  here from the start, following engagement's V2 rather than webhook's
--  (webhook's V2 predates V40's ADR-043 nullability change) -- Risk emits
--  nothing today, but a future platform-scoped event should not need a second
--  migration to be representable.
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
--  outbox_events -- this module's own outbox (ADR-010, ADR-016, ADR-025,
--  ADR-037, ADR-043)
-- ----------------------------------------------------------------------------
CREATE TABLE outbox_events (
    event_id            VARCHAR(40)              NOT NULL,
    -- Nullable: see the file header (ADR-043). Every event about a real
    -- merchant still carries it.
    merchant_id         VARCHAR(40),
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
