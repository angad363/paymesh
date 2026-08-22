-- =============================================================================
-- V35: asynchronous CSV exports. SDD 19.2, ADR-034.
--
-- A merchant asks for a window, gets a rex_ id back immediately, and a
-- scheduled job renders the CSV. The same record-then-do shape Notification
-- and Webhook use, for the same reason: the request commits a row, and the
-- work that could be slow or could fail happens where a retry is cheap.
--
-- THE CSV LIVES IN A COLUMN, AND THAT IS A DELIBERATE CEILING.
--
-- The honest options were a TEXT column or object storage, and there is no
-- object storage in this project -- infrastructure/ is empty and SDD 27 is
-- not started. Returning a signed download URL to a bucket that does not
-- exist would be the kind of fiction this codebase avoids elsewhere (see
-- ADR-033 on the SIMULATED sender, which is named simulated rather than
-- dressed up as email). A column is small, transactional, and truthful about
-- its limit: it will not hold a million-row export. The upgrade path is a
-- storage_uri column beside content and a writer that fills one or the other.
--
-- WHAT IS DELIBERATELY NOT HERE
--
--   * No report_type. There is ONE export -- the merchant's facts in a window,
--     which is the data behind both reports. A second type would be a second
--     CSV writer for a shape the summaries already return as JSON.
--   * No expiry and no retention sweep. SDD 19.3's audit_exports names an
--     expiry; nothing here reads one, and a column nothing reads is a promise
--     nothing keeps.
--   * No attempt counter. Generating a CSV from rows this process just wrote
--     cannot fail the way an HTTP delivery can; a generation that throws is
--     logged, left PENDING, and retried on the next pass forever, which is the
--     correct budget for work that is deterministic.
-- =============================================================================

CREATE TABLE report_exports (
    report_export_id  VARCHAR(40)   NOT NULL,

    merchant_id       VARCHAR(40)   NOT NULL,

    -- The requested window, half-open [window_from, window_to). Stored so the
    -- generated file can be re-derived and so GET can tell the merchant what
    -- they actually asked for rather than what they meant to.
    window_from       TIMESTAMPTZ   NOT NULL,
    window_to         TIMESTAMPTZ   NOT NULL,

    status            VARCHAR(16)   NOT NULL,

    -- Rows written, excluding the header. NULL until COMPLETED -- a zero would
    -- claim an empty export where the truth is that nothing has run yet.
    row_count         INTEGER,

    -- The rendered CSV. NULL until COMPLETED. See the header on why this is a
    -- column and not a URL.
    content           TEXT,

    -- Why generation failed, for a FAILED row. Nothing sets this today: the
    -- generator only marks FAILED on a window it can never satisfy, and a
    -- transient throw stays PENDING.
    failure_reason    TEXT,

    requested_at      TIMESTAMPTZ   NOT NULL,
    completed_at      TIMESTAMPTZ,

    CONSTRAINT pk_report_exports PRIMARY KEY (report_export_id),

    CONSTRAINT fk_report_exports_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),

    CONSTRAINT ck_report_exports_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),

    -- A window that ends before it starts is not a small mistake, it is an
    -- export that can only ever be empty. The request record refuses it too;
    -- this is what makes it true.
    CONSTRAINT ck_report_exports_window CHECK (window_from < window_to),

    -- A COMPLETED export HAS its file, its count and its timestamp; a PENDING
    -- or FAILED one has none of them. Without this a row could read COMPLETED
    -- with a null content, and GET would hand the merchant a 200 with an empty
    -- body -- a successful-looking answer to a download that did not happen.
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

-- The generator's hot query: PENDING rows, oldest first. Partial, because
-- COMPLETED rows accumulate forever and the generator never wants to see one.
-- The same partial index idx_notifications_pending is, for the same reason.
CREATE INDEX idx_report_exports_pending
    ON report_exports (requested_at)
    WHERE status = 'PENDING';
