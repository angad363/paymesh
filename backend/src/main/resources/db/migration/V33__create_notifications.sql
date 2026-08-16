-- =============================================================================
-- V33: merchants get told what happened. SDD 19.1, ADR-033.
--
-- ONE table, not the three the phase-2 plan named. The other two are cut for
-- reasons this codebase has already accepted once each:
--
--   * notification_templates -- a template is static per-event content that a
--     deploy changes, so it is code (NotificationTemplates), exactly as Risk's
--     rules are code and not a table (ADR-030). Add the table the day a
--     non-engineer must edit copy without a deploy.
--
--   * delivery_attempts -- "a log wearing a table's clothes", the same phrase
--     ADR-028 section 3 used to cut webhook_delivery_attempts. attempt_count
--     and last_error below answer what support asks. Add the table if
--     per-attempt forensics are ever needed; it attaches without touching this.
--
-- And there is no next_attempt_at / backoff schedule: the sender is simulated
-- and cannot fail, so a failed attempt (from a future real sender) is simply
-- retried on the next ordinary pass until the attempt budget is spent. Webhook's
-- next_attempt_at is the pattern to copy when a real sender makes that matter.
-- =============================================================================

CREATE TABLE notifications (
    notification_id   VARCHAR(40)              NOT NULL,

    -- Who the notification is for. The merchant IS the recipient; there is no
    -- separate address column, because the sender is simulated and resolving a
    -- real one would make this a leaf no longer (a MerchantLookup port). ADR-033.
    merchant_id       VARCHAR(40)              NOT NULL,

    -- THE OUTBOX EVENT THAT PRODUCED THIS ROW, and the natural key that makes
    -- the handler idempotent. A redelivered event lands on
    -- uq_notifications_source_event and the handler does nothing -- the same
    -- trick webhook_events plays with uq_webhook_events_source. This is the
    -- evt_ id from shared.outbox, not this row's own nfn_ id.
    source_event_id   VARCHAR(40)              NOT NULL,

    event_type        VARCHAR(64)              NOT NULL,

    -- Rendered once, at record time, from NotificationTemplates. Stored rather
    -- than re-rendered on read so a template edit cannot change what a merchant
    -- was already told.
    subject           VARCHAR(256)             NOT NULL,
    body              TEXT                     NOT NULL,

    status            VARCHAR(16)              NOT NULL,

    -- How many times the sender has been asked to deliver this. Zero until the
    -- dispatcher first tries. Only ever moves past zero once a sender that can
    -- fail is installed; the simulated one succeeds on the first attempt.
    attempt_count     INTEGER                  NOT NULL DEFAULT 0,

    -- The last failure text, for support. Null on a PENDING row never tried and
    -- on a SENT row.
    last_error        TEXT,

    created_at        TIMESTAMPTZ              NOT NULL,
    updated_at        TIMESTAMPTZ              NOT NULL,

    -- When the simulated send succeeded. Null until then.
    sent_at           TIMESTAMPTZ,

    CONSTRAINT pk_notifications PRIMARY KEY (notification_id),

    CONSTRAINT fk_notifications_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),

    -- ONE NOTIFICATION PER SOURCE EVENT. The idempotency guard: the handler
    -- checks for the row before writing, and this makes losing that race a
    -- refused insert rather than a duplicate message to a merchant.
    CONSTRAINT uq_notifications_source_event UNIQUE (source_event_id),

    CONSTRAINT ck_notifications_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),

    CONSTRAINT ck_notifications_attempt_count
        CHECK (attempt_count >= 0),

    -- A SENT row has a sent_at; a PENDING or FAILED one does not. Without this a
    -- notification could read SENT with no record of when, which is the one fact
    -- support most wants.
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

-- The dispatcher's hot query: PENDING rows, oldest first. Partial, because SENT
-- and FAILED rows accumulate forever and the dispatcher never wants to see one.
CREATE INDEX idx_notifications_pending
    ON notifications (created_at)
    WHERE status = 'PENDING';
