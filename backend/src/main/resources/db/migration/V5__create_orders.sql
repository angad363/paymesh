-- ============================================================================
--  V5__create_orders.sql
--  Creates the order capability's table: orders (a merchant's commercial
--  intent -- what is being bought and how much is due). SDD section 11.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entity, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  THE INVARIANT THIS SCHEMA EXISTS TO PROTECT: an order belongs to exactly one
--  merchant, states an amount that cannot be negative or overpaid, and is never
--  visible to another tenant. merchant_id is therefore NOT NULL, leads every
--  index, and is the first column of every uniqueness rule -- so "unique" always
--  means "unique within one merchant", never globally.
--
--  ON MONEY: amounts are BIGINT counts of MINOR UNITS with the currency held in
--  its own column. No NUMERIC, no DOUBLE. A decimal type invites a rounding
--  policy nobody agreed on, and floating point cannot represent 0.10 at all --
--  money that cannot be represented cannot be audited.
--
--  ON STATUS: the CHECK below admits five states, but only PENDING -> CANCELLED
--  is reachable from application code today. PAID, PARTIALLY_PAID and EXPIRED
--  are declared now so that Payment (which drives the first two) and the expiry
--  sweeper (the third) need no migration the moment they land.
-- ============================================================================

-- The FK below points at (merchant_id, customer_id), not at customer_id alone,
-- so that an order can only ever name a customer of ITS OWN merchant. Postgres
-- requires a unique constraint on the referenced columns for that to be legal,
-- and customers has one only on (merchant_id, merchant_reference), so the pair
-- is declared unique here. It is redundant with pk_customers -- customer_id is
-- already unique on its own -- and that redundancy is the point: it costs one
-- index and it turns "an order never points at another tenant's customer" from
-- an application promise into something the database refuses to break.
ALTER TABLE customers
    ADD CONSTRAINT uq_customers_merchant_customer UNIQUE (merchant_id, customer_id);


CREATE TABLE orders (
    -- Opaque, application-generated identifier "ord_" + UUID (ADR-003).
    -- Always exactly 40 chars (4 prefix + 36 UUID). VARCHAR(40) fits it exactly.
    -- Application-minted (OrderId.generate), so no IDENTITY/serial: a sequential
    -- id would let a merchant infer the platform's order volume.
    order_id            VARCHAR(40)              NOT NULL,

    -- Owning tenant. Same "mrc_" + UUID shape as merchants.merchant_id.
    -- NOT NULL and foreign-keyed: an order with no merchant, or with a merchant
    -- that does not exist, is corruption -- the DB refuses it rather than
    -- trusting every future code path to remember.
    merchant_id         VARCHAR(40)              NOT NULL,

    -- Optional link to the buyer. NULL for a guest checkout, which is why there
    -- is no NOT NULL here: requiring a customer would force merchants to mint
    -- throwaway buyer records for one-off purchases.
    customer_id         VARCHAR(40),

    -- The merchant's OWN reference for this purchase (their cart id, invoice
    -- number). Optional, and unique per merchant (see uq_orders_merchant_ref).
    -- It is the second, independent de-duplication rule: Idempotency-Key answers
    -- "is this the same REQUEST?", this answers "does this merchant already have
    -- an order for this PURCHASE?". A genuine double-submit carrying a fresh
    -- key still lands here.
    merchant_order_ref  VARCHAR(100),

    -- The amount due, as a count of MINOR UNITS (paise, cents). Positive by
    -- CHECK: an order for nothing, or for a negative sum, is not an order.
    amount_minor        BIGINT                   NOT NULL,

    -- ISO 4217 alphabetic code, held SEPARATELY from the amount because 1000
    -- means nothing without it. CHAR(3) is the exact width; the CHECK below
    -- keeps it uppercase letters, so 'inr' and 'INR' can never both appear.
    currency            CHAR(3)                  NOT NULL,

    -- How much of amount_minor has actually been paid. 0 until the Payment
    -- capability exists; kept here rather than derived from payment rows so the
    -- overpayment CHECK below can be a schema rule instead of a query.
    amount_paid_minor   BIGINT                   NOT NULL,

    -- Lifecycle state, persisted as the enum NAME (string), never its ordinal,
    -- so reordering the Java enum cannot corrupt existing rows.
    status              VARCHAR(32)              NOT NULL,

    -- Free text shown to the buyer at checkout. Optional.
    description         VARCHAR(500),

    -- Merchant-defined key/value pairs, echoed back untouched on every read.
    -- JSONB (not JSON, not TEXT): stored parsed, so a malformed object is
    -- rejected on write rather than discovered on read. Capped in the domain
    -- (16 keys, 40-char keys, 500-char values) so it cannot become free storage.
    metadata            JSONB,

    -- When the order stops being payable. Optional. NOTHING SWEEPS THIS YET:
    -- an expired-but-PENDING order is currently only wrong in the reporting
    -- sense, and Payment needs the sweeper anyway.
    expires_at          TIMESTAMP WITH TIME ZONE,

    -- Why the merchant cancelled, and when. Paired with status by
    -- ck_orders_cancellation below. These two columns are also why there is no
    -- order_state_history table yet: with exactly one reachable transition they
    -- carry the whole history. The table arrives when a third transition does.
    cancellation_reason VARCHAR(200),
    cancelled_at        TIMESTAMP WITH TIME ZONE,

    -- Optimistic-lock counter (@Version, SDD 23.3). Hibernate increments it on
    -- every update and refuses one whose version has moved, so two concurrent
    -- writers cannot both read PENDING and both decide they may cancel. NOT
    -- NULL with no DEFAULT: the application always supplies it, so a row that
    -- appeared some other way is a bug the DB will name.
    version             INTEGER                  NOT NULL,

    -- Creation / last-modification instants. TIMESTAMP WITH TIME ZONE so every
    -- value is an unambiguous absolute point in time (stored as UTC), matching
    -- java.time.Instant. Supplied by the app (Clock bean), so no DB DEFAULT or
    -- trigger -- the application stays the source of time, which is also what
    -- lets a test create two orders at exactly the same instant on purpose.
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_orders PRIMARY KEY (order_id),

    -- RESTRICT (the default) rather than CASCADE: deleting a merchant that still
    -- has orders must fail loudly, not silently erase the record of what a buyer
    -- was charged for.
    CONSTRAINT fk_orders_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    -- COMPOSITE on purpose (see the ALTER above): referencing customer_id alone
    -- would let an order name any customer in the platform, and the application
    -- check that prevents it is advisory -- it can pass and then the row it
    -- checked can change. This is the guarantee; that check exists to turn the
    -- violation into a readable 422 instead of a 500.
    CONSTRAINT fk_orders_customer FOREIGN KEY (merchant_id, customer_id)
        REFERENCES customers (merchant_id, customer_id),

    -- The merchant's reference is unique WITHIN a merchant, not globally: two
    -- merchants may both use "ORDER-7788" and both must succeed. Postgres treats
    -- NULLs as distinct in a unique index, so any number of orders may carry no
    -- reference at all. Enforced at the DB because the application's
    -- existsByMerchantOrderReference check is a check, not a lock: two concurrent
    -- creates can both pass it, and this is what stops both from landing.
    CONSTRAINT uq_orders_merchant_ref UNIQUE (merchant_id, merchant_order_ref),

    -- An order for zero or a negative sum is not an order. Defense in depth: the
    -- domain refuses it too, but a bad write from any path cannot poison the row.
    CONSTRAINT ck_orders_amount_positive CHECK (amount_minor > 0),

    -- THE OVERPAYMENT RULE, stated at the schema level: an order can never be
    -- paid more than it asks for, and can never be paid a negative amount. When
    -- Payment lands, a bug in its arithmetic fails here rather than quietly
    -- creating a balance the ledger cannot explain.
    CONSTRAINT ck_orders_amount_paid CHECK (
        amount_paid_minor >= 0 AND amount_paid_minor <= amount_minor
    ),

    -- Uppercase letters only, so the same currency has exactly one spelling.
    CONSTRAINT ck_orders_currency CHECK (currency ~ '^[A-Z]{3}$'),

    -- The DB itself rejects any status outside the known set, so a bad write from
    -- any path (not just the app) cannot poison the column.
    CONSTRAINT ck_orders_status CHECK (
        status IN ('PENDING', 'PAID', 'PARTIALLY_PAID', 'CANCELLED', 'EXPIRED')
    ),

    -- Keeps the cancellation pair honest in both directions: a CANCELLED order
    -- always says when, and no other status may claim a cancellation instant.
    -- Without the second half, a later transition could leave a stale
    -- cancelled_at behind and make the row unreadable to an auditor.
    CONSTRAINT ck_orders_cancellation CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL)
    )
);

-- Access-path indexes. Every one is composite and leads with merchant_id, because
-- every query in this capability is tenant-scoped -- a leading merchant_id means
-- the planner can never choose a plan that scans another merchant's rows.
--
-- Reading one order by id needs no index here: pk_orders already resolves it, and
-- the merchant_id predicate is a filter on the single row it returns.

-- The list endpoint's default page: this merchant's orders, newest first. DESC
-- matches the sort so the index supplies the order for free. order_id is the
-- second sort key (the cursor's tiebreak) and rides along in the index, so a page
-- boundary that falls between two orders sharing a created_at is resolved without
-- a sort step -- and, more importantly, deterministically.
CREATE INDEX idx_orders_merchant_created_at ON orders (merchant_id, created_at DESC, order_id DESC);

-- The ?status= filter on the same endpoint.
CREATE INDEX idx_orders_merchant_status ON orders (merchant_id, status);

-- "This customer's orders". Partial: guest orders have no customer_id and are
-- unsearchable that way regardless, so keeping them out costs nothing and keeps
-- the index small when most checkouts are guest checkouts.
CREATE INDEX idx_orders_merchant_customer ON orders (merchant_id, customer_id)
    WHERE customer_id IS NOT NULL;
