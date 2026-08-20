-- =============================================================================
-- V34: the reporting projection. SDD 19.2, ADR-034.
--
-- ONE table, not one per report. Both reports and the CSV export are the same
-- question asked three ways -- (which merchant, when, what kind of thing, how
-- much, which currency, about what) -- so they read one fact table and differ
-- only in the WHERE and the GROUP BY. A payments table and a settlements table
-- would hold the same six columns twice and give the export two sources to
-- join.
--
-- APPEND-ONLY, KEYED BY THE EVENT THAT PRODUCED THE ROW.
--
-- The obvious alternative is a row per payment intent, updated as the payment
-- succeeds and is later refunded. That design has to be correct under
-- concurrent and out-of-order delivery: two consumers reading the same row and
-- each adding their own amount lose one of them, which is precisely the race
-- ApplyRefundSucceededService takes a row lock to avoid. A row per EVENT has
-- no such race, because it never reads before it writes.
--
-- It also makes idempotency free. source_event_id is the primary key, so a
-- redelivered event is a refused insert rather than a double-counted payment
-- -- the same trick notifications and webhook_events play with a unique index,
-- except here the natural key IS the primary key and no second column is
-- needed to carry one.
--
-- WHAT IS DELIBERATELY NOT HERE
--
--   * No pre-aggregated daily rollup. A GROUP BY over a merchant's own facts
--     is cheap at this size, and a rollup is strictly A-plus-a-cache: it needs
--     this table anyway, because the export selects rows. Add it the day a
--     report is measurably slow, not before.
--
--   * No status column and no mutation. A fact is what a producer announced at
--     a moment; it does not change afterwards. Corrections arrive as further
--     facts, exactly as the ledger corrects itself with reversal transactions
--     rather than edits (ADR-018).
--
--   * No FX and no cross-currency total. currency is on every row and every
--     report groups by it, so nothing in this capability can add USD to EUR.
-- =============================================================================

CREATE TABLE report_facts (
    -- THE OUTBOX EVENT THAT PRODUCED THIS ROW, and the primary key. Not a
    -- separate rpt_ identifier: a fact is not addressable on its own -- there
    -- is no GET /reports/facts/{id} and there will not be, because a merchant
    -- asks for a summary or an export, never for one fact. ADR-003's opaque
    -- prefixed ids are for things a client names; this is not one.
    source_event_id  VARCHAR(40)   NOT NULL,

    merchant_id      VARCHAR(40)   NOT NULL,

    event_type       VARCHAR(64)   NOT NULL,

    -- What the fact is ABOUT: a pi_ for the payment types, a ref_ for a
    -- refund, an stl_ for the settlement ones. Deliberately untyped and
    -- unconstrained beyond being present -- Reporting must not learn the id
    -- vocabularies of six other capabilities to store a string it only ever
    -- echoes back in a CSV column.
    subject_id       VARCHAR(64)   NOT NULL,

    -- The order a payment belongs to, when the event carries one. NULL on the
    -- settlement types, which are about a batch and not an order.
    order_id         VARCHAR(40),

    currency         CHAR(3)       NOT NULL,

    -- Minor units, always non-negative. Direction is carried by event_type
    -- (a refund.succeeded is money going out), the same separation the ledger
    -- makes between a positive amount and a stored direction.
    amount_minor     BIGINT        NOT NULL,

    -- THE PRODUCER'S CLOCK, which is what a report is about. A merchant asking
    -- for August wants the payments that happened in August, not the ones this
    -- projection happened to ingest in August.
    occurred_at      TIMESTAMPTZ   NOT NULL,

    -- REPORTING'S OWN CLOCK, and the reason asOf can be honest. The newest
    -- recorded_at is how far this projection has actually caught up; every
    -- report response reports it rather than reporting "now", because "now"
    -- would claim a currency the projection does not have while events sit
    -- unpublished in the outbox (ADR-016).
    recorded_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_report_facts PRIMARY KEY (source_event_id),

    CONSTRAINT fk_report_facts_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id),

    -- The subscribed set, pinned. A seventh type reaching this table without
    -- this constraint being widened means a handler was registered whose
    -- extraction nobody reviewed -- and a report silently counting a kind of
    -- fact it was never designed to count is the failure mode a reporting
    -- table has.
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

    -- Zero is legal (a payment can fail for nothing meaningful), negative is
    -- not: direction lives in event_type.
    CONSTRAINT ck_report_facts_amount CHECK (amount_minor >= 0),

    CONSTRAINT ck_report_facts_currency CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_report_facts_source_event_id_format
        CHECK (is_prefixed_id(source_event_id, 'evt_')),

    CONSTRAINT ck_report_facts_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_')),

    CONSTRAINT ck_report_facts_order_id_format
        CHECK (is_prefixed_id(order_id, 'ord_'))
);

-- EVERY read this capability makes is (one merchant, a window of occurred_at),
-- so the index is the composite in that order. merchant_id first because it is
-- the equality predicate and the tenant boundary; occurred_at second because
-- it is the range.
CREATE INDEX idx_report_facts_merchant_occurred
    ON report_facts (merchant_id, occurred_at);

-- asOf is MAX(recorded_at) for one merchant, on every single report response.
-- Without this it is a scan of the merchant's whole history to answer "how
-- fresh is this", which would make the freshness signal the slowest part of
-- the report that carries it.
CREATE INDEX idx_report_facts_merchant_recorded
    ON report_facts (merchant_id, recorded_at DESC);
