-- ============================================================================
--  V25__create_webhook_deliveries.sql
--  One row per (event, endpoint): the attempt to tell one merchant one thing.
--  ADR-028, SDD section 18.
--
--  THE TABLE THIS MIGRATION DELIBERATELY DOES NOT CREATE is
--  webhook_delivery_attempts, which SDD 18.4 lists and which phase-2-plan.md
--  reserved this migration for. A row per attempt would carry status, duration
--  and a response excerpt -- real forensics, but forensics that attempts,
--  last_status_code, last_response_excerpt and next_attempt_at below already
--  answer for a merchant debugging a failure. It is a log wearing a table's
--  clothes. It can be added later without touching webhook_events, which is the
--  table that carries an invariant. ADR-028 section 3, which amends the plan.
--
--  THE INVARIANT THIS CAPABILITY EXISTS UNDER, and it is the reason no HTTP
--  happens anywhere near here:
--
--      a merchant endpoint being down must never affect a payment
--
--  The fan-out handler runs inside the event dispatcher's transaction and only
--  ever writes rows -- one webhook_events row and one PENDING row here per
--  subscribed endpoint. The sending happens later, on a timer, one transaction
--  per row. A network call inside an open database transaction is how a slow
--  merchant becomes a stalled payment path.
-- ============================================================================

CREATE TABLE webhook_deliveries (
    -- "whd_" + UUID (ADR-003, extended by ADR-028).
    delivery_id            VARCHAR(40)              NOT NULL,

    webhook_event_id       VARCHAR(40)              NOT NULL,
    endpoint_id            VARCHAR(40)              NOT NULL,
    merchant_id            VARCHAR(40)              NOT NULL,

    status                 VARCHAR(20)              NOT NULL,

    -- Attempts made against THIS delivery. The budget is SIX, spaced by five waits
    -- (1m, 5m, 30m, 2h, 6h), and then the delivery is FAILED. 8h36m, which
    -- is deliberately long enough to survive a merchant's overnight deploy and
    -- deliberately short of a day so a dead endpoint does not hold rows
    -- indefinitely. A different scale from ADR-025's outbox budget on purpose:
    -- an outbox event failing repeatedly is a bug, a merchant returning 503 for
    -- six hours is ordinary operation. ADR-028 section 6.
    attempts               INTEGER                  NOT NULL DEFAULT 0,

    -- NULL once the delivery is terminal. The dispatcher's claim query is
    -- (status, next_attempt_at), indexed below.
    next_attempt_at        TIMESTAMPTZ,

    -- What came back last. NULL until something does -- a connection refused or
    -- a timeout never produces a status code, which is itself informative.
    last_status_code       INTEGER,

    -- Capped at 512 characters by the application, which also caps the READ at
    -- 4 KiB rather than truncating after reading. A merchant returning 10 MB
    -- must not become PayMesh's memory problem. ADR-028 section 6.
    last_response_excerpt  VARCHAR(512),

    created_at             TIMESTAMPTZ              NOT NULL,
    updated_at             TIMESTAMPTZ              NOT NULL,

    CONSTRAINT pk_webhook_deliveries PRIMARY KEY (delivery_id),

    CONSTRAINT fk_webhook_deliveries_event
        FOREIGN KEY (webhook_event_id) REFERENCES webhook_events (webhook_event_id),

    CONSTRAINT fk_webhook_deliveries_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),

    -- COMPOSITE, NOT A SINGLE-COLUMN FK TO endpoint_id, and the difference is
    -- tenant isolation rather than tidiness. A single-column reference would
    -- let a row name another merchant's endpoint and still satisfy the
    -- database; carrying merchant_id into the key makes that unrepresentable.
    -- V6 exists because exactly this was got wrong once already.
    CONSTRAINT fk_webhook_deliveries_endpoint
        FOREIGN KEY (merchant_id, endpoint_id)
        REFERENCES webhook_endpoints (merchant_id, endpoint_id),

    -- ONE DELIVERY PER (EVENT, ENDPOINT). This is what makes a redelivered
    -- outbox event impossible to fan out twice, rather than merely unlikely --
    -- the handler's ON CONFLICT DO NOTHING on webhook_events covers the common
    -- path, and this covers the case where that row already existed.
    CONSTRAINT uq_webhook_deliveries_event_endpoint
        UNIQUE (webhook_event_id, endpoint_id),

    CONSTRAINT ck_webhook_deliveries_status
        CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),

    CONSTRAINT ck_webhook_deliveries_attempts
        CHECK (attempts >= 0),

    -- A PENDING delivery is always scheduled; a terminal one never is. Without
    -- this a delivery could go PENDING with no next_attempt_at and simply never
    -- be claimed again -- stuck, with nothing saying so.
    CONSTRAINT ck_webhook_deliveries_schedule
        CHECK (
            (status = 'PENDING' AND next_attempt_at IS NOT NULL)
            OR
            (status <> 'PENDING' AND next_attempt_at IS NULL)
        )
);

-- The dispatcher's hot query: due PENDING rows, oldest first. Partial, because
-- terminal rows accumulate forever and the dispatcher never wants to see one.
CREATE INDEX idx_webhook_deliveries_due
    ON webhook_deliveries (next_attempt_at)
    WHERE status = 'PENDING';

-- The merchant-facing list endpoint's filter.
CREATE INDEX idx_webhook_deliveries_merchant_endpoint_status
    ON webhook_deliveries (merchant_id, endpoint_id, status);
