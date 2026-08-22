-- =============================================================================
-- V36: the operational and security audit log. SDD 19.3, ADR-035.
--
-- Two tables: audit_events (the append-only log) and audit_exports (the async
-- CSV, the same shape report_exports has in V35).
--
-- WHY THIS IS NOT THE LEDGER, AND WHY IT IS NOT REPORTING.
--
-- Money movement lives in the Ledger: double-entry, balanced, its own
-- immutability triggers. This table records who did a PRIVILEGED or OPERATIONAL
-- thing -- suspended a merchant, rotated a signing secret, revoked a platform
-- role -- and the two are deliberately not merged. A ledger entry answers "where
-- did the money go"; an audit event answers "who touched the machine". Merging
-- them would put an actor and an IP hash next to a debit, and put a compliance
-- reviewer's list of privileged actions behind a double-entry query it does not
-- want. ADR-035 states this at length.
--
-- Audit is ALSO not Reporting/Notification, though they look alike. Those are
-- pure event consumers: everything they record already rides the outbox. Audit's
-- subjects do not -- a merchant suspension, a secret rotation and a platform-role
-- grant emit no outbox event. So the row is written by an in-process
-- AuditRecorder port (shared.audit) that the privileged service calls inside its
-- OWN transaction, so a committed privileged action always carries its audit row
-- and a rolled-back one leaves no false trail. ADR-035 section 2.
--
-- APPEND-ONLY, ENFORCED BY A TRIGGER, EXACTLY AS ledger_entries IS (V15).
--
-- "Append-only" in a comment is a hope. A BEFORE UPDATE OR DELETE trigger that
-- RAISEs is the rule the database keeps. A REVOKE would be the lazier line but
-- it is granted per role: it says nothing about a migration running as the owner
-- and is not in force under Testcontainers, where the test connects as the
-- superuser. An invariant absent where it is tested is not an invariant. This
-- fires for everyone. The integration test issues a raw UPDATE and the database
-- refuses it with the application entirely out of the path.
--
-- NO PLAINTEXT VALUES, NO PII, NO SECRETS.
--
-- before_hash / after_hash are SHA-256 of the state either side of the action,
-- never the values themselves -- the log proves THAT something changed and WHO,
-- not what the old signing secret was. ip_hash is the same: a hash, so the log
-- can say "same source as that other action" without storing an address it would
-- then have to protect. The AuditRecorder does the hashing, so a caller cannot
-- hand this table a plaintext secret by forgetting to.
-- =============================================================================

CREATE TABLE audit_events (
    audit_event_id  VARCHAR(40)   NOT NULL,

    -- WHO. A USER acting through the API, the SYSTEM (a scheduled job, e.g.
    -- reconciliation recovery), or a PROVIDER (a signed callback). actor_id is
    -- the usr_ for a USER and null for SYSTEM -- a job has no operator.
    actor_type      VARCHAR(16)   NOT NULL,
    actor_id        VARCHAR(64),

    -- The tenant the action was performed ON, when there is one. NULL for a
    -- platform-wide action (granting a platform role targets a user, not a
    -- merchant). Not a tenant-isolation column: this log is read by platform
    -- staff across tenants, so merchant_id is a FILTER here, not a fence.
    merchant_id     VARCHAR(40),

    -- WHAT, as a dotted action string: merchant.suspended, webhook.secret_rotated,
    -- user.platform_admin_granted. A vocabulary, not free text; the recorder's
    -- callers use constants.
    action          VARCHAR(64)   NOT NULL,

    -- WHICH object the action touched: ('merchant', mrc_...), ('webhook_endpoint',
    -- whe_...), ('user', usr_...). resource_id is opaque and not format-checked --
    -- it holds ids from every capability, so a single prefix CHECK cannot fit.
    resource_type   VARCHAR(64)   NOT NULL,
    resource_id     VARCHAR(64),

    -- WHY, when the action carries one. A suspension reason, a rotation note.
    -- Free text, capped. Null when the action needs no explanation.
    reason          TEXT,

    -- State either side of the change, HASHED. See the header. Null when the
    -- action has no before (a creation) or no after (a pure read that is still
    -- worth logging, e.g. an export of another tenant's data).
    before_hash     VARCHAR(64),
    after_hash      VARCHAR(64),

    -- The source address, HASHED. Null for a SYSTEM actor, and for a USER action
    -- recorded below the HTTP boundary where no request is in scope.
    ip_hash         VARCHAR(64),

    -- WHEN, the recorder's clock. UTC, like every timestamp here.
    occurred_at     TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_audit_events PRIMARY KEY (audit_event_id),

    -- No foreign key to merchants. An audit row must survive the thing it
    -- describes: closing and later purging a merchant must not delete the record
    -- that the closure happened. The format CHECK keeps merchant_id well-shaped
    -- without tying the row's lifetime to the merchant's.
    CONSTRAINT ck_audit_events_actor_type
        CHECK (actor_type IN ('USER', 'SYSTEM', 'PROVIDER')),

    -- A SYSTEM actor has no operator id; a USER must have one. Without this a
    -- USER action could be recorded with a null actor -- an audit entry that
    -- cannot answer the one question it exists for.
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

-- The read surface's two filters, both newest-first: a merchant's history and an
-- action's history. occurred_at DESC because a support engineer wants the most
-- recent first, and audit_event_id breaks the tie so paging is stable.
CREATE INDEX idx_audit_events_merchant
    ON audit_events (merchant_id, occurred_at DESC, audit_event_id DESC)
    WHERE merchant_id IS NOT NULL;

CREATE INDEX idx_audit_events_action
    ON audit_events (action, occurred_at DESC, audit_event_id DESC);

CREATE INDEX idx_audit_events_recent
    ON audit_events (occurred_at DESC, audit_event_id DESC);

-- -----------------------------------------------------------------------------
-- APPEND-ONLY. The same two functions ledger_entries and ledger_transactions
-- use in V15, worded for this table. A correction to an audit log is a NEW row
-- (someone noticed the log was wrong and says so), never an edit -- editing the
-- record of what happened is the single thing an audit log exists to prevent.
-- -----------------------------------------------------------------------------
CREATE FUNCTION audit_events_are_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'Audit events are immutable; the log is append-only'
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_audit_events_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW
    EXECUTE FUNCTION audit_events_are_immutable();


-- =============================================================================
-- audit_exports: the asynchronous CSV, SDD 19.3. Copied from report_exports
-- (V35) deliberately -- same record-then-generate shape, same TEXT-column
-- ceiling, same off-under-dev generator. The differences from report_exports,
-- and why:
--
--   * NO merchant_id ownership column. A report export belongs to the merchant
--     who asked; an audit export is requested by PLATFORM STAFF and reads across
--     tenants. It carries the requesting operator (requested_by) instead, and an
--     OPTIONAL merchant_filter that narrows the window to one tenant.
--
--   * requested_by is a usr_, never null: an audit export is a privileged read
--     and the log records who ran it -- the export request is itself auditable.
--
-- Same as V35: no report_type (one export shape), no expiry/retention sweep
-- (a column nothing reads is a promise nothing keeps), no attempt counter
-- (generating from rows this process can read is deterministic; a throw stays
-- PENDING and retries).
-- =============================================================================

CREATE TABLE audit_exports (
    audit_export_id  VARCHAR(40)   NOT NULL,

    -- The platform operator who requested it. The read is privileged, so who ran
    -- it is itself a fact worth keeping.
    requested_by     VARCHAR(64)   NOT NULL,

    -- Narrow the export to one tenant, or null for the whole platform in the
    -- window. A FILTER, like audit_events.merchant_id, not an owner.
    merchant_filter  VARCHAR(40),

    -- The requested window, half-open [window_from, window_to).
    window_from      TIMESTAMPTZ   NOT NULL,
    window_to        TIMESTAMPTZ   NOT NULL,

    status           VARCHAR(16)   NOT NULL,

    -- Rows written, excluding the header. NULL until COMPLETED.
    row_count        INTEGER,

    -- The rendered CSV. NULL until COMPLETED. A column, not a URL, for the
    -- reason V35's header gives: there is no object storage in this project.
    content          TEXT,

    failure_reason   TEXT,

    requested_at     TIMESTAMPTZ   NOT NULL,
    completed_at     TIMESTAMPTZ,

    CONSTRAINT pk_audit_exports PRIMARY KEY (audit_export_id),

    CONSTRAINT ck_audit_exports_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),

    CONSTRAINT ck_audit_exports_window CHECK (window_from < window_to),

    -- A COMPLETED export HAS its file, count and timestamp; a PENDING or FAILED
    -- one has none. Same guard as report_exports: without it a COMPLETED row
    -- could carry a null content and GET would 200 an empty download.
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

-- The generator's hot query: PENDING rows, oldest first. Partial, because
-- terminal rows accumulate forever and the generator never wants to see one.
-- The same partial index idx_report_exports_pending is.
CREATE INDEX idx_audit_exports_pending
    ON audit_exports (requested_at)
    WHERE status = 'PENDING';
