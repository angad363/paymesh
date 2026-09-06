-- ============================================================================
--  V1__adopt_webhook_schema.sql
--  Webhook's own tables. ADR-028 (the capability), ADR-038 (schema-per-service),
--  ADR-039 (the merchant_ref projection), ADR-042 (the extraction).
--
--  THIS MODULE'S OWN FLYWAY HISTORY, SEPARATE FROM THE MONOLITH'S (ADR-042,
--  following the ADR-041 recipe). These tables already exist in the shared
--  cluster's `webhook` schema: webhook_endpoints/webhook_events/webhook_deliveries
--  are the monolith's V24/V25, moved into this schema by V38; merchant_ref is
--  V39's per-schema projection. This file is byte-for-byte that live shape, so a
--  FRESH database (a new environment, a Testcontainers run) gets the same schema
--  either way. Against the EXISTING shared dev database, `baseline-on-migrate`
--  (application.yaml) makes Flyway ADOPT the already-present tables at this
--  version instead of re-running CREATE TABLE against a schema that already has
--  them.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  WHAT IS DIFFERENT FROM THE MONOLITH'S ORIGINAL V24/V25, AND WHY IT IS STILL
--  "byte-for-byte the live shape": the monolith's `fk_webhook_endpoints_merchant`
--  / `fk_webhook_events_merchant` / `fk_webhook_deliveries_merchant` constraints
--  were already DROPPED by the monolith's own V39 in favour of this same
--  merchant_ref projection, before this module ever existed. The live `webhook`
--  schema this migration adopts has never had those three constraints since V39
--  shipped, so omitting them here is adopting reality, not diverging from it --
--  and it could not be otherwise: `webhook_svc` has no `merchants` table in its
--  reach to reference (ADR-038 fencing). The identifier-format CHECK on each
--  merchant_id column (V26) is kept; only the cross-schema FK is gone.
-- ============================================================================


-- --- the shared identifier-format predicate (V26 in the monolith) ----------
-- ADR-003's <prefix>_<uuid> shape, enforced in the database rather than only
-- in each XxxId value object's compact constructor. Defined in `public` (not
-- `webhook`) because it is a platform-wide predicate several schemas rely on,
-- exactly where the monolith's own V26 put it. CREATE OR REPLACE, not CREATE:
-- on a database that already has it (any environment sharing a cluster with
-- the monolith) this is a same-definition no-op; on a genuinely fresh one
-- (Testcontainers) it is the only place this module defines it -- the same
-- call provider-sim's own V1 makes (ADR-041).
CREATE OR REPLACE FUNCTION public.is_prefixed_id(value text, prefix text)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
    PARALLEL SAFE
AS $$
SELECT value ~ ('^' || prefix ||
                '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
$$;


-- ----------------------------------------------------------------------------
--  webhook_endpoints -- where a merchant wants to be told (SDD 18.4)
-- ----------------------------------------------------------------------------
CREATE TABLE webhook_endpoints (
    endpoint_id                 VARCHAR(40)              NOT NULL,
    merchant_id                 VARCHAR(40)              NOT NULL,
    url                         VARCHAR(2048)            NOT NULL,
    secret_version              INTEGER                  NOT NULL DEFAULT 1,
    previous_secret_version     INTEGER,
    previous_secret_expires_at  TIMESTAMPTZ,
    subscriptions               JSONB                    NOT NULL,
    status                      VARCHAR(20)              NOT NULL,
    consecutive_failures        INTEGER                  NOT NULL DEFAULT 0,
    version                     BIGINT                   NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ              NOT NULL,
    updated_at                  TIMESTAMPTZ              NOT NULL,

    CONSTRAINT pk_webhook_endpoints PRIMARY KEY (endpoint_id),

    -- REDUNDANT WITH THE PRIMARY KEY, AND THAT REDUNDANCY IS THE POINT (V24):
    -- webhook_deliveries carries a composite FK on (merchant_id, endpoint_id) so
    -- a delivery cannot reference another tenant's endpoint.
    CONSTRAINT uq_webhook_endpoints_merchant_endpoint
        UNIQUE (merchant_id, endpoint_id),

    CONSTRAINT uq_webhook_endpoints_merchant_url
        UNIQUE (merchant_id, url),

    CONSTRAINT ck_webhook_endpoints_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),

    CONSTRAINT ck_webhook_endpoints_url_https
        CHECK (url ~* '^https://'),

    CONSTRAINT ck_webhook_endpoints_rotation_window
        CHECK (
            (previous_secret_version IS NULL AND previous_secret_expires_at IS NULL)
            OR
            (previous_secret_version IS NOT NULL AND previous_secret_expires_at IS NOT NULL)
        ),

    CONSTRAINT ck_webhook_endpoints_secret_version
        CHECK (secret_version >= 1),

    CONSTRAINT ck_webhook_endpoints_consecutive_failures
        CHECK (consecutive_failures >= 0),

    -- Identifier-shape CHECKs (V26). is_prefixed_id lives in `public`, resolved
    -- through this deployable's own search_path (webhook, public).
    CONSTRAINT ck_webhook_endpoints_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_webhook_endpoints_id_format
        CHECK (is_prefixed_id(endpoint_id, 'whe_'))
);

CREATE INDEX idx_webhook_endpoints_merchant_status
    ON webhook_endpoints (merchant_id, status);


-- ----------------------------------------------------------------------------
--  webhook_events -- the EXTERNAL event, frozen once (SDD 18.2)
-- ----------------------------------------------------------------------------
CREATE TABLE webhook_events (
    webhook_event_id  VARCHAR(40)              NOT NULL,
    merchant_id       VARCHAR(40)              NOT NULL,
    source_event_id   VARCHAR(40)              NOT NULL,
    event_type        VARCHAR(60)              NOT NULL,
    schema_version    INTEGER                  NOT NULL,
    payload           TEXT                     NOT NULL,
    occurred_at       TIMESTAMPTZ              NOT NULL,
    created_at        TIMESTAMPTZ              NOT NULL,

    CONSTRAINT pk_webhook_events PRIMARY KEY (webhook_event_id),

    CONSTRAINT uq_webhook_events_source_event
        UNIQUE (source_event_id),

    CONSTRAINT ck_webhook_events_schema_version
        CHECK (schema_version >= 1),

    CONSTRAINT ck_webhook_events_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_webhook_events_id_format
        CHECK (is_prefixed_id(webhook_event_id, 'whv_')),
    -- source_event_id carries the OUTBOX prefix (evt_), not a webhook one: it
    -- names the outbox event this row was translated from.
    CONSTRAINT ck_webhook_events_source_event_id_format
        CHECK (is_prefixed_id(source_event_id, 'evt_'))
);

CREATE INDEX idx_webhook_events_merchant_occurred
    ON webhook_events (merchant_id, occurred_at);

CREATE FUNCTION webhook_events_are_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'Webhook events are immutable; a replay must resend the original bytes'
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_webhook_events_immutable
    BEFORE UPDATE OR DELETE ON webhook_events
    FOR EACH ROW
    EXECUTE FUNCTION webhook_events_are_immutable();


-- ----------------------------------------------------------------------------
--  webhook_deliveries -- one row per (event, endpoint) (ADR-028)
-- ----------------------------------------------------------------------------
CREATE TABLE webhook_deliveries (
    delivery_id            VARCHAR(40)              NOT NULL,
    webhook_event_id       VARCHAR(40)              NOT NULL,
    endpoint_id            VARCHAR(40)              NOT NULL,
    merchant_id            VARCHAR(40)              NOT NULL,
    status                 VARCHAR(20)              NOT NULL,
    attempts               INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at        TIMESTAMPTZ,
    last_status_code       INTEGER,
    last_response_excerpt  VARCHAR(512),
    created_at             TIMESTAMPTZ              NOT NULL,
    updated_at             TIMESTAMPTZ              NOT NULL,

    CONSTRAINT pk_webhook_deliveries PRIMARY KEY (delivery_id),

    CONSTRAINT fk_webhook_deliveries_event
        FOREIGN KEY (webhook_event_id) REFERENCES webhook_events (webhook_event_id),

    -- COMPOSITE, NOT A SINGLE-COLUMN FK TO endpoint_id: carrying merchant_id
    -- into the key makes "this row names another tenant's endpoint"
    -- unrepresentable, same reasoning as V6.
    CONSTRAINT fk_webhook_deliveries_endpoint
        FOREIGN KEY (merchant_id, endpoint_id)
        REFERENCES webhook_endpoints (merchant_id, endpoint_id),

    CONSTRAINT uq_webhook_deliveries_event_endpoint
        UNIQUE (webhook_event_id, endpoint_id),

    CONSTRAINT ck_webhook_deliveries_status
        CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),

    CONSTRAINT ck_webhook_deliveries_attempts
        CHECK (attempts >= 0),

    CONSTRAINT ck_webhook_deliveries_schedule
        CHECK (
            (status = 'PENDING' AND next_attempt_at IS NOT NULL)
            OR
            (status <> 'PENDING' AND next_attempt_at IS NULL)
        ),

    CONSTRAINT ck_webhook_deliveries_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_webhook_deliveries_event_id_format
        CHECK (is_prefixed_id(webhook_event_id, 'whv_')),
    CONSTRAINT ck_webhook_deliveries_endpoint_id_format
        CHECK (is_prefixed_id(endpoint_id, 'whe_')),
    CONSTRAINT ck_webhook_deliveries_id_format
        CHECK (is_prefixed_id(delivery_id, 'whd_'))
);

CREATE INDEX idx_webhook_deliveries_due
    ON webhook_deliveries (next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_webhook_deliveries_merchant_endpoint_status
    ON webhook_deliveries (merchant_id, endpoint_id, status);


-- ----------------------------------------------------------------------------
--  merchant_ref -- the read model that replaced the `* -> merchants` FK
--  (ADR-039). This module's own copy: fed by MerchantRefProjector consuming
--  merchant.* events over this deployable's own Kafka consumer group
--  (ADR-042 section 5), answering MerchantStatusGate for every authenticated
--  webhook-endpoint write.
-- ----------------------------------------------------------------------------
CREATE TABLE merchant_ref (
    merchant_id  TEXT        NOT NULL,
    status       TEXT        NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_merchant_ref PRIMARY KEY (merchant_id),
    CONSTRAINT ck_merchant_ref_id
        CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_merchant_ref_status
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'CLOSED'))
);
