-- ============================================================================
--  V1__adopt_engagement_schema.sql
--  Engagement's own tables: Notification (ADR-033), Reporting (ADR-034), Audit
--  (ADR-035), schema-per-service (ADR-038), the merchant_ref-free FK drop
--  (ADR-039), the extraction (ADR-043).
--
--  THIS MODULE'S OWN FLYWAY HISTORY, SEPARATE FROM THE MONOLITH'S (ADR-043,
--  following the ADR-041/ADR-042 recipe). These five tables already exist in
--  the shared cluster's `engagement` schema: notifications/report_facts/
--  report_exports are the monolith's V33/V34/V35, audit_events/audit_exports
--  are its V36, all moved into this schema by V38. This file is byte-for-byte
--  that live shape, so a FRESH database (a new environment, a Testcontainers
--  run) gets the same schema either way. Against the EXISTING shared dev
--  database, `baseline-on-migrate` (application.yaml) makes Flyway ADOPT the
--  already-present tables at this version instead of re-running CREATE TABLE
--  against a schema that already has them.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  WHAT IS DIFFERENT FROM THE MONOLITH'S ORIGINAL V33/V34/V35/V36, AND WHY IT
--  IS STILL "byte-for-byte the live shape": notifications/report_facts/
--  report_exports each had a `fk_*_merchant` FOREIGN KEY to `merchants`,
--  already DROPPED by the monolith's own V39 in favour of the merchant_ref
--  projection this module does NOT carry (notification/reporting/audit read
--  no merchant status -- verified by grep before this module was cut: none of
--  the three import MerchantStatusGate or merchant_ref). The live `engagement`
--  schema this migration adopts has never had those three constraints since
--  V39 shipped, so omitting them here is adopting reality, not diverging from
--  it -- and it could not be otherwise: `engagement_svc` has no `merchants`
--  table in its reach to reference (ADR-038 fencing). audit_events and
--  audit_exports never had a merchant FK to begin with (V36's own comment: "An
--  audit row must survive the thing it describes"). The identifier-format
--  CHECK on each merchant_id column (V26) is kept.
-- ============================================================================


-- --- the shared identifier-format predicate (V26 in the monolith) ----------
-- ADR-003's <prefix>_<uuid> shape, enforced in the database rather than only
-- in each XxxId value object's compact constructor. Defined in `public` (not
-- `engagement`) because it is a platform-wide predicate several schemas rely
-- on, exactly where the monolith's own V26 put it. CREATE OR REPLACE, not
-- CREATE: on a database that already has it (any environment sharing a
-- cluster with the monolith) this is a same-definition no-op; on a genuinely
-- fresh one (Testcontainers) it is the only place this module defines it --
-- the same call provider-sim's and webhook's own V1 make (ADR-041, ADR-042).
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
--  notifications -- merchants get told what happened (SDD 19.1, ADR-033)
-- ----------------------------------------------------------------------------
CREATE TABLE notifications (
    notification_id   VARCHAR(40)              NOT NULL,
    merchant_id       VARCHAR(40)              NOT NULL,
    source_event_id   VARCHAR(40)              NOT NULL,
    event_type        VARCHAR(64)              NOT NULL,
    subject           VARCHAR(256)             NOT NULL,
    body              TEXT                     NOT NULL,
    status            VARCHAR(16)              NOT NULL,
    attempt_count     INTEGER                  NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMPTZ              NOT NULL,
    updated_at        TIMESTAMPTZ              NOT NULL,
    sent_at           TIMESTAMPTZ,

    CONSTRAINT pk_notifications PRIMARY KEY (notification_id),

    CONSTRAINT uq_notifications_source_event UNIQUE (source_event_id),

    CONSTRAINT ck_notifications_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),

    CONSTRAINT ck_notifications_attempt_count
        CHECK (attempt_count >= 0),

    CONSTRAINT ck_notifications_sent_at
        CHECK (
            (status = 'SENT' AND sent_at IS NOT NULL)
            OR
            (status <> 'SENT' AND sent_at IS NULL)
        ),

    CONSTRAINT ck_notifications_id_format
        CHECK (is_prefixed_id(notification_id, 'nfn_')),

    CONSTRAINT ck_notifications_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);

CREATE INDEX idx_notifications_pending
    ON notifications (created_at)
    WHERE status = 'PENDING';


-- ----------------------------------------------------------------------------
--  report_facts -- the reporting projection (SDD 19.2, ADR-034)
-- ----------------------------------------------------------------------------
CREATE TABLE report_facts (
    source_event_id  VARCHAR(40)   NOT NULL,
    merchant_id      VARCHAR(40)   NOT NULL,
    event_type       VARCHAR(64)   NOT NULL,
    subject_id       VARCHAR(64)   NOT NULL,
    order_id         VARCHAR(40),
    currency         CHAR(3)       NOT NULL,
    amount_minor     BIGINT        NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL,
    recorded_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_report_facts PRIMARY KEY (source_event_id),

    CONSTRAINT ck_report_facts_event_type CHECK (
        event_type IN (
            'payment.succeeded',
            'payment.failed',
            'refund.succeeded',
            'settlement.batch_cut',
            'payout.paid',
            'payout.returned'
        )
    ),

    CONSTRAINT ck_report_facts_amount CHECK (amount_minor >= 0),

    CONSTRAINT ck_report_facts_currency CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_report_facts_source_event_id_format
        CHECK (is_prefixed_id(source_event_id, 'evt_')),

    CONSTRAINT ck_report_facts_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_')),

    CONSTRAINT ck_report_facts_order_id_format
        CHECK (is_prefixed_id(order_id, 'ord_'))
);

CREATE INDEX idx_report_facts_merchant_occurred
    ON report_facts (merchant_id, occurred_at);

CREATE INDEX idx_report_facts_merchant_recorded
    ON report_facts (merchant_id, recorded_at DESC);


-- ----------------------------------------------------------------------------
--  report_exports -- asynchronous CSV exports (SDD 19.2, ADR-034)
-- ----------------------------------------------------------------------------
CREATE TABLE report_exports (
    report_export_id  VARCHAR(40)   NOT NULL,
    merchant_id       VARCHAR(40)   NOT NULL,
    window_from       TIMESTAMPTZ   NOT NULL,
    window_to         TIMESTAMPTZ   NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    row_count         INTEGER,
    content           TEXT,
    failure_reason    TEXT,
    requested_at      TIMESTAMPTZ   NOT NULL,
    completed_at      TIMESTAMPTZ,

    CONSTRAINT pk_report_exports PRIMARY KEY (report_export_id),

    CONSTRAINT ck_report_exports_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),

    CONSTRAINT ck_report_exports_window CHECK (window_from < window_to),

    CONSTRAINT ck_report_exports_completed CHECK (
        (status = 'COMPLETED'
            AND content IS NOT NULL
            AND row_count IS NOT NULL
            AND completed_at IS NOT NULL)
        OR
        (status <> 'COMPLETED'
            AND content IS NULL
            AND row_count IS NULL
            AND completed_at IS NULL)
    ),

    CONSTRAINT ck_report_exports_row_count CHECK (row_count IS NULL OR row_count >= 0),

    CONSTRAINT ck_report_exports_id_format
        CHECK (is_prefixed_id(report_export_id, 'rex_')),

    CONSTRAINT ck_report_exports_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);

CREATE INDEX idx_report_exports_pending
    ON report_exports (requested_at)
    WHERE status = 'PENDING';


-- ----------------------------------------------------------------------------
--  audit_events -- the operational and security audit log (SDD 19.3, ADR-035)
-- ----------------------------------------------------------------------------
CREATE TABLE audit_events (
    audit_event_id  VARCHAR(40)   NOT NULL,
    actor_type      VARCHAR(16)   NOT NULL,
    actor_id        VARCHAR(64),
    merchant_id     VARCHAR(40),
    action          VARCHAR(64)   NOT NULL,
    resource_type   VARCHAR(64)   NOT NULL,
    resource_id     VARCHAR(64),
    reason          TEXT,
    before_hash     VARCHAR(64),
    after_hash      VARCHAR(64),
    ip_hash         VARCHAR(64),
    occurred_at     TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_audit_events PRIMARY KEY (audit_event_id),

    CONSTRAINT ck_audit_events_actor_type
        CHECK (actor_type IN ('USER', 'SYSTEM', 'PROVIDER')),

    CONSTRAINT ck_audit_events_actor_id
        CHECK (
            (actor_type = 'SYSTEM' AND actor_id IS NULL)
            OR
            (actor_type <> 'SYSTEM' AND actor_id IS NOT NULL)
        ),

    CONSTRAINT ck_audit_events_id_format
        CHECK (is_prefixed_id(audit_event_id, 'aud_')),

    CONSTRAINT ck_audit_events_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);

CREATE INDEX idx_audit_events_merchant
    ON audit_events (merchant_id, occurred_at DESC, audit_event_id DESC)
    WHERE merchant_id IS NOT NULL;

CREATE INDEX idx_audit_events_action
    ON audit_events (action, occurred_at DESC, audit_event_id DESC);

CREATE INDEX idx_audit_events_actor
    ON audit_events (actor_id, occurred_at DESC, audit_event_id DESC)
    WHERE actor_id IS NOT NULL;

CREATE INDEX idx_audit_events_recent
    ON audit_events (occurred_at DESC, audit_event_id DESC);

-- APPEND-ONLY, ENFORCED BY A TRIGGER. Adopted, not recreated: this function and
-- trigger already exist in the live `engagement` schema (pre-V38, moved with
-- the table by SET SCHEMA). CREATE OR REPLACE / DROP+CREATE TRIGGER make this
-- file idempotent against both a fresh database and the adopted one.
CREATE OR REPLACE FUNCTION audit_events_are_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'Audit events are immutable; the log is append-only'
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tr_audit_events_immutable ON audit_events;

CREATE TRIGGER tr_audit_events_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW
    EXECUTE FUNCTION audit_events_are_immutable();


-- ----------------------------------------------------------------------------
--  audit_exports -- the asynchronous CSV, platform-staff scoped (SDD 19.3)
-- ----------------------------------------------------------------------------
CREATE TABLE audit_exports (
    audit_export_id  VARCHAR(40)   NOT NULL,
    requested_by     VARCHAR(64)   NOT NULL,
    merchant_filter  VARCHAR(40),
    window_from      TIMESTAMPTZ   NOT NULL,
    window_to        TIMESTAMPTZ   NOT NULL,
    status           VARCHAR(16)   NOT NULL,
    row_count        INTEGER,
    content          TEXT,
    failure_reason   TEXT,
    requested_at     TIMESTAMPTZ   NOT NULL,
    completed_at     TIMESTAMPTZ,

    CONSTRAINT pk_audit_exports PRIMARY KEY (audit_export_id),

    CONSTRAINT ck_audit_exports_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),

    CONSTRAINT ck_audit_exports_window CHECK (window_from < window_to),

    CONSTRAINT ck_audit_exports_completed CHECK (
        (status = 'COMPLETED'
            AND content IS NOT NULL
            AND row_count IS NOT NULL
            AND completed_at IS NOT NULL)
        OR
        (status <> 'COMPLETED'
            AND content IS NULL
            AND row_count IS NULL
            AND completed_at IS NULL)
    ),

    CONSTRAINT ck_audit_exports_row_count CHECK (row_count IS NULL OR row_count >= 0),

    CONSTRAINT ck_audit_exports_id_format
        CHECK (is_prefixed_id(audit_export_id, 'aex_')),

    CONSTRAINT ck_audit_exports_merchant_filter_format
        CHECK (is_prefixed_id(merchant_filter, 'mrc_'))
);

CREATE INDEX idx_audit_exports_pending
    ON audit_exports (requested_at)
    WHERE status = 'PENDING';
