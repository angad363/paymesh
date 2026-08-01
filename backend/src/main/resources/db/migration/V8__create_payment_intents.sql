-- ============================================================================
--  V8__create_payment_intents.sql
--  Creates payment_intents and payment_state_history. SDD section 12, ADR-011.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  An order says what is owed. A payment intent says how it is being collected.
--  It is operational state, NOT the financial record: a SUCCEEDED intent does
--  not move a balance, and the Ledger -- which does not exist yet -- remains the
--  source of truth when it lands.
--
--  ALL TEN STATUSES ARE DECLARED HERE and only two are reachable today. This is
--  the precedent V5 set for orders (PAID, PARTIALLY_PAID and EXPIRED declared
--  and unreached): the later PRs in this design widen the state machine and none
--  of them should need a migration to do it.
-- ============================================================================

-- Prerequisite for the composite foreign key below. orders' primary key is
-- (order_id) alone, and PostgreSQL will not accept a reference to
-- (merchant_id, order_id) without a unique constraint over exactly those
-- columns. Redundant with pk_orders, and that redundancy is the price of a
-- tenant-safe foreign key -- the same trade V5 made when it added
-- uq_customers_merchant_customer so that orders could point at customers
-- without being able to point across tenants.
ALTER TABLE orders
    ADD CONSTRAINT uq_orders_merchant_order UNIQUE (merchant_id, order_id);


CREATE TABLE payment_intents (
    -- Opaque, application-generated identifier "pi_" + UUID (ADR-003). Always
    -- exactly 39 chars (3 prefix + 36 UUID); VARCHAR(40) matches the other id
    -- columns rather than shaving a byte off one table.
    --
    -- "pi_" and not "pay_", although ADR-003 lists both: "pay_" is ambiguous
    -- between this and a future "payment" record, and identifiers cannot be
    -- changed once they are issued to a merchant. "pay_" stays reserved.
    payment_intent_id       VARCHAR(40)              NOT NULL,

    -- Owning tenant. Every read in the application scopes by this column, and
    -- an intent belonging to another merchant is reported as 404, never 403.
    merchant_id             VARCHAR(40)              NOT NULL,

    -- The obligation being collected. REQUIRED, unlike the customer link: an
    -- intent with no order has nothing to be for, and the amount rule below has
    -- nothing to check itself against.
    order_id                VARCHAR(40)              NOT NULL,

    -- Copied from the order when it has one. Nullable because a guest checkout
    -- produces an order with no customer, and an intent cannot invent one.
    customer_id             VARCHAR(40),

    -- Minor units, always positive, currency held separately. Fixed at creation
    -- and never edited -- SDD 12.6 makes them immutable after confirmation and
    -- this design is stricter, because nothing legitimately needs to change them
    -- before that either.
    amount_minor            BIGINT                   NOT NULL,
    currency                CHAR(3)                  NOT NULL,

    -- AUTOMATIC captures as soon as the provider authorizes; MANUAL stops at
    -- AUTHORIZED and waits for the merchant. Nothing reaches either state until
    -- PR 4, but the choice is made at creation, so the column is written from
    -- PR 2 onward.
    capture_method          VARCHAR(16)              NOT NULL,

    -- CARD, UPI, NET_BANKING or WALLET, chosen when the merchant attaches one.
    -- NULL until then, which is why the CHECK below tolerates NULL in exactly
    -- the two states that precede an attach. No Java code maps this column yet:
    -- attach is PR 3 and it owns the vocabulary.
    payment_method_type     VARCHAR(20),

    status                  VARCHAR(32)              NOT NULL,

    -- How much of amount_minor has actually been captured. Zero until a capture
    -- happens (PR 5). Declared now so no later PR needs a migration.
    captured_amount_minor   BIGINT                   NOT NULL,

    -- Never moves in this design; the Refund capability owns it. Declared for
    -- the same reason orders.amount_paid_minor was.
    refunded_amount_minor   BIGINT                   NOT NULL,

    -- Populated by a provider callback (PR 4). The message is the provider's
    -- own, after redaction -- raw provider payloads are never stored here.
    failure_code            VARCHAR(60),
    failure_message         VARCHAR(500),

    cancellation_reason     VARCHAR(200),
    cancelled_at            TIMESTAMP WITH TIME ZONE,

    description             VARCHAR(500),

    -- JSONB, capped in the domain rather than here: a merchant may not use this
    -- table as free key-value storage.
    metadata                JSONB,

    -- Optimistic lock (SDD 23.3). Two concurrent writers cannot both act on the
    -- state they read.
    version                 INTEGER                  NOT NULL,

    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_payment_intents PRIMARY KEY (payment_intent_id),

    -- Prerequisite for the composite foreign keys that payment_attempts (V9) and
    -- provider_callbacks (V10) will point at this table with, and for
    -- payment_state_history below. Same trade as uq_orders_merchant_order above.
    CONSTRAINT uq_payment_intents_merchant_intent UNIQUE (merchant_id, payment_intent_id),

    -- merchant_id IS the tenant column here, so a single-column reference is
    -- correct and complete.
    CONSTRAINT fk_payment_intents_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    -- COMPOSITE, AND THAT IS THE WHOLE POINT. A foreign key on order_id alone
    -- would permit an intent to collect against ANY order on the platform,
    -- leaving only an advisory application check between one merchant and
    -- another tenant's obligations. This is the exact flaw review found in
    -- fk_orders_customer last session.
    CONSTRAINT fk_payment_intents_order FOREIGN KEY (merchant_id, order_id)
        REFERENCES orders (merchant_id, order_id),

    -- Same reasoning. customer_id stays nullable and PostgreSQL foreign keys
    -- default to MATCH SIMPLE, so a guest intent with no customer is not
    -- constrained by this and still inserts.
    CONSTRAINT fk_payment_intents_customer FOREIGN KEY (merchant_id, customer_id)
        REFERENCES customers (merchant_id, customer_id),

    CONSTRAINT ck_payment_intents_amount CHECK (amount_minor > 0),

    CONSTRAINT ck_payment_intents_currency CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_payment_intents_capture_method
        CHECK (capture_method IN ('AUTOMATIC', 'MANUAL')),

    CONSTRAINT ck_payment_intents_status CHECK (status IN (
        'REQUIRES_PAYMENT_METHOD',
        'REQUIRES_CONFIRMATION',
        'PROCESSING',
        'REQUIRES_ACTION',
        'AUTHORIZED',
        'SUCCEEDED',
        'FAILED',
        'CANCELLED',
        'PARTIALLY_REFUNDED',
        'REFUNDED'
    )),

    -- Overcapture is not a business decision to be reviewed later; it is a
    -- number that must not exist.
    CONSTRAINT ck_payment_intents_captured
        CHECK (captured_amount_minor >= 0 AND captured_amount_minor <= amount_minor),

    -- Refunding more than was captured is the same class of impossibility.
    CONSTRAINT ck_payment_intents_refunded
        CHECK (refunded_amount_minor >= 0 AND refunded_amount_minor <= captured_amount_minor),

    -- A cancelled intent without a timestamp cannot be audited, and a
    -- cancellation timestamp on a live intent is a lie about its state. Both
    -- directions are constrained so neither can drift.
    CONSTRAINT ck_payment_intents_cancelled_at CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL)
    ),

    -- Past the attach step a payment method is always known. The two exempt
    -- states are the one before attach and the one an intent cancelled before
    -- attach ends in.
    CONSTRAINT ck_payment_intents_method_known CHECK (
        status IN ('REQUIRES_PAYMENT_METHOD', 'CANCELLED')
        OR payment_method_type IS NOT NULL
    ),

    -- SUCCEEDED means money was taken. Zero captured with a succeeded status is
    -- a contradiction the Ledger would later have to reconcile.
    CONSTRAINT ck_payment_intents_succeeded_captured
        CHECK (status <> 'SUCCEEDED' OR captured_amount_minor > 0)
);

-- AT MOST ONE LIVE INTENT PER ORDER, ENFORCED BY THE DATABASE (ADR-011).
--
-- A rule enforced only in application code is not enforced: two concurrent
-- POST /api/v1/payment-intents for one order both pass a pre-check and both
-- insert. This partial unique index is what makes the second one lose. The
-- application's pre-check exists only to produce a friendlier message and is
-- never trusted.
--
-- The exclusion set is exactly FAILED and CANCELLED. SUCCEEDED,
-- PARTIALLY_REFUNDED and REFUNDED still occupy the slot, because an order that
-- has already been paid must not acquire a second intent.
--
-- THE COST, STATED RATHER THAN DISCOVERED LATER: an intent that cannot reach
-- FAILED or CANCELLED holds its order's only slot forever, and a stuck intent
-- is therefore a stuck order. Every state a customer can strand an intent in has
-- a merchant cancel route for exactly this reason -- except PROCESSING, which is
-- deliberately uncancellable because an in-flight attempt may already have
-- succeeded at the provider. A lost callback strands the order and recovery is
-- manual until a PROCESSING timeout and provider reconciliation exist
-- (SDD 21.4, 24.1). Neither is in scope for this design.
CREATE UNIQUE INDEX uq_payment_intents_live_per_order
    ON payment_intents (merchant_id, order_id)
    WHERE status NOT IN ('FAILED', 'CANCELLED');

-- The list endpoint's default page, read straight off the index with no sort
-- step. Three columns in these directions because keyset pagination compares
-- the PAIR (created_at, payment_intent_id): ordering by the timestamp alone is
-- not a total order, so a page boundary between two intents sharing an instant
-- silently skips one.
CREATE INDEX idx_payment_intents_merchant_created_at
    ON payment_intents (merchant_id, created_at DESC, payment_intent_id DESC);

-- The status filter on that same list.
CREATE INDEX idx_payment_intents_merchant_status
    ON payment_intents (merchant_id, status);

-- "Which intents does this order have?" -- the merchant-facing filter, and the
-- lookup the create path uses to explain a slot conflict.
CREATE INDEX idx_payment_intents_merchant_order
    ON payment_intents (merchant_id, order_id);


-- ============================================================================
--  payment_state_history: every transition an intent has made, append-only.
--
--  IT SHIPS NOW, NOT WITH THE PR THAT MAKES THE TRANSITIONS INTERESTING. Order
--  deferred its history table because it had one reachable transition; Payment
--  has two on day one and six by the time provider callbacks land. A history
--  table added later would leave every intent created before it with a hole in
--  its timeline, and a timeline with a hole cannot be audited -- which is the
--  only thing a history table is for.
--
--  No endpoint reads it yet. Exposing a timeline is a separate decision; writing
--  one that is already complete when that decision is made is not.
-- ============================================================================
CREATE TABLE payment_state_history (
    -- A plain sequence, not a prefixed identifier. ADR-003 governs identifiers
    -- that appear in an API and this one never does -- the same reasoning that
    -- left idempotency_records without an id column.
    payment_state_history_id BIGINT GENERATED ALWAYS AS IDENTITY,

    merchant_id              VARCHAR(40)              NOT NULL,
    payment_intent_id        VARCHAR(40)              NOT NULL,

    -- NULL for the creation row: an intent that has just been created came from
    -- nowhere, and writing its initial status in both columns would claim a
    -- transition that never happened.
    from_status              VARCHAR(32),
    to_status                VARCHAR(32)              NOT NULL,

    -- Who caused it: MERCHANT (an API call), PROVIDER (a callback) or SYSTEM (a
    -- sweeper or reconciliation job). Only MERCHANT is reachable today.
    actor_type               VARCHAR(20)              NOT NULL,

    -- Which one, when it is knowable and useful: a merchant id, a provider name.
    -- Nullable because SYSTEM actions have no principal to name.
    actor_id                 VARCHAR(80),

    reason                   VARCHAR(200),

    occurred_at              TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_payment_state_history PRIMARY KEY (payment_state_history_id),

    -- Composite for the same tenant-safety reason as everything above.
    CONSTRAINT fk_payment_state_history_intent
        FOREIGN KEY (merchant_id, payment_intent_id)
        REFERENCES payment_intents (merchant_id, payment_intent_id),

    CONSTRAINT ck_payment_state_history_actor
        CHECK (actor_type IN ('MERCHANT', 'PROVIDER', 'SYSTEM'))
);

-- One intent's timeline, oldest first. The only access pattern this table has.
CREATE INDEX idx_payment_state_history_intent
    ON payment_state_history (merchant_id, payment_intent_id, occurred_at);
