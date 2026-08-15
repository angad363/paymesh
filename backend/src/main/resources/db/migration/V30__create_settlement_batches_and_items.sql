-- =============================================================================
-- V30: a merchant's available balance becomes a batch. SDD 17.1/17.4, ADR-032.
--
-- V29 made money settleable. This is the thing that settles it: a batch is the
-- whole of one merchant's available balance in one currency at one instant,
-- itemised by the payment each part of it came from.
--
-- WHY THE ITEMS ARE PAYMENTS AND NOT RELEASE JOURNALS
--
-- The obvious itemisation is "one row per funds-released journal", and it is
-- wrong for the same reason releasing the gross was wrong (ADR-031): a refund
-- landing after release debits MERCHANT_AVAILABLE without producing a release
-- journal to point at. The items would then sum to more than the balance they
-- describe.
--
-- So an item is a PAYMENT's net contribution to available -- its release credit
-- minus every post-release refund debit attributable to it -- and the sum of
-- the items is the available balance exactly, because every entry in the
-- account references a payment intent. A payment refunded past its own release
-- contributes a NEGATIVE item, which is SDD 17.1's "adjustments" arriving as a
-- row rather than as a column.
-- =============================================================================


CREATE TABLE settlement_batches (
    -- stl_<uuid>, the identifier ADR-003 reserved for this capability.
    settlement_batch_id   VARCHAR(40)              NOT NULL,

    merchant_id           VARCHAR(40)              NOT NULL,

    currency              CHAR(3)                  NOT NULL,

    -- WHAT THE MERCHANT IS OWED BY THIS BATCH, in minor units.
    --
    -- Signed BIGINT rather than a positive amount with a direction, because
    -- unlike a ledger entry a batch has only one meaning: money on its way to
    -- the merchant. A batch is only ever cut for a POSITIVE net (see
    -- ck_settlement_batches_net) -- the type is wide enough for the sum of its
    -- items, which individually may be negative.
    net_amount_minor      BIGINT                   NOT NULL,

    -- NO gross, fees OR adjustments COLUMNS, AND THAT IS SDD 17.1 DELIBERATELY
    -- ONLY PARTLY IMPLEMENTED.
    --
    -- There is no fee schedule anywhere in this codebase (the Ledger's own
    -- javadoc says so, and ADR-018 refuses to invent a rate), so gross and net
    -- would be the same number written twice and fees would be a column that is
    -- always zero. Adjustments are not a column here either: a negative item IS
    -- the adjustment, itemised and traceable to the payment that caused it,
    -- which a single rolled-up figure is not. When a fee schedule exists it
    -- arrives as a fee ITEM and the trigger below keeps the arithmetic honest
    -- with no schema change at all.

    -- The instant the batch was cut. THE PERIOD, and there is no period_start,
    -- because a batch takes everything available rather than a window: the
    -- items name exactly what is in it, and a start instant would be a second,
    -- less exact way of saying the same thing.
    cut_at                TIMESTAMP WITH TIME ZONE NOT NULL,

    status                VARCHAR(20)              NOT NULL,

    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_settlement_batches PRIMARY KEY (settlement_batch_id),

    -- Composite, so a payout row cannot name a batch of one merchant while
    -- claiming another. Same shape as the ledger's, and the same reason.
    CONSTRAINT uq_settlement_batches_merchant_batch
        UNIQUE (settlement_batch_id, merchant_id),

    -- The currency travels with the id too, so an item cannot be denominated
    -- differently from the batch holding it.
    CONSTRAINT uq_settlement_batches_batch_currency
        UNIQUE (settlement_batch_id, currency),

    CONSTRAINT fk_settlement_batches_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    -- PENDING_PAYOUT: cut, funds moved out of available, no payout submitted
    -- yet. PAID: the provider confirmed. RETURNED: the payout failed terminally
    -- and the funds went back to available through a new journal.
    --
    -- There is no CLOSED and no OPEN. A batch is cut complete -- the balance it
    -- describes has already moved -- so a state meaning "still accumulating"
    -- would be a state no row is ever in.
    CONSTRAINT ck_settlement_batches_status CHECK (status IN (
        'PENDING_PAYOUT', 'PAID', 'RETURNED'
    )),

    -- A batch for nothing, or for money owed the other way, is not a payout.
    -- The job simply does not cut one; this makes that a fact rather than a
    -- convention.
    CONSTRAINT ck_settlement_batches_net CHECK (net_amount_minor > 0),

    CONSTRAINT ck_settlement_batches_currency CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_settlement_batches_id_format
        CHECK (is_prefixed_id(settlement_batch_id, 'stl_')),

    CONSTRAINT ck_settlement_batches_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);

-- The merchant's own statement list, newest first, and the query the batch job
-- runs to find out whether one is already in flight.
CREATE INDEX ix_settlement_batches_merchant_cut
    ON settlement_batches (merchant_id, cut_at DESC);


CREATE TABLE settlement_items (
    settlement_item_id    VARCHAR(40)              NOT NULL,

    settlement_batch_id   VARCHAR(40)              NOT NULL,

    -- Denormalised from the batch so the composite foreign keys below have
    -- something to match on. Never set independently; the FK is what makes that
    -- true rather than a comment asking nicely.
    merchant_id           VARCHAR(40)              NOT NULL,
    currency              CHAR(3)                  NOT NULL,

    -- WHICH PAYMENT THIS PART OF THE BATCH CAME FROM.
    --
    -- The payment intent, not the release journal, for the reason at the top of
    -- this file: a payment's contribution is its release net of anything
    -- refunded after it, and only the payment identifies all of that.
    payment_intent_id     VARCHAR(40)              NOT NULL,

    -- SIGNED, and negative is legitimate: a payment refunded past its own
    -- release owes money back, and a batch that silently dropped it would pay
    -- out more than the merchant has. This is the row SDD 17.1 calls an
    -- adjustment.
    amount_minor          BIGINT                   NOT NULL,

    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_settlement_items PRIMARY KEY (settlement_item_id),

    -- ONE ROW PER PAYMENT PER BATCH. A payment appearing twice in one batch
    -- would double-count it against a total the trigger in V32 then happily
    -- agrees with, because both halves are in the sum.
    CONSTRAINT uq_settlement_items_batch_payment
        UNIQUE (settlement_batch_id, payment_intent_id),

    CONSTRAINT fk_settlement_items_batch FOREIGN KEY (settlement_batch_id)
        REFERENCES settlement_batches (settlement_batch_id),

    -- COMPOSITE, both of them: an item cannot belong to one merchant's batch
    -- while naming another's, and cannot be denominated differently from the
    -- batch it sums into. Same mechanism as fk_orders_customer (V5).
    CONSTRAINT fk_settlement_items_batch_merchant
        FOREIGN KEY (settlement_batch_id, merchant_id)
        REFERENCES settlement_batches (settlement_batch_id, merchant_id),

    CONSTRAINT fk_settlement_items_batch_currency
        FOREIGN KEY (settlement_batch_id, currency)
        REFERENCES settlement_batches (settlement_batch_id, currency),

    -- A zero item says nothing happened and still occupies the unique slot a
    -- real contribution would need.
    CONSTRAINT ck_settlement_items_amount CHECK (amount_minor <> 0),

    CONSTRAINT ck_settlement_items_id_format
        CHECK (is_prefixed_id(settlement_item_id, 'sti_')),

    CONSTRAINT ck_settlement_items_payment_id_format
        CHECK (is_prefixed_id(payment_intent_id, 'pi_'))
);

CREATE INDEX ix_settlement_items_batch ON settlement_items (settlement_batch_id);


-- -----------------------------------------------------------------------------
-- THE OTHER TWO COLUMNS SDD 17.4 SPECIFIES, ARRIVING NOW THAT THEY HAVE READERS.
--
-- ADR-031 held these back on the grounds that "a column whose semantics are
-- decided by a capability that does not exist is a column that gets them
-- wrong", and said PR 4 would add them as an ordinary ALTER. This is that
-- ALTER.
--
-- STILL NOT HERE: SDD 17.4's payout SCHEDULE. The job's interval is the
-- schedule, and a per-merchant cron expression would be a second schedule that
-- can disagree with the first -- and nothing in this platform can run a batch
-- at a time the job is not awake anyway.
-- -----------------------------------------------------------------------------

-- Where the money goes. NULL means "not configured", and a merchant with no
-- destination is never batched: cutting one would move their funds out of
-- available and into a transit account with nowhere to go, which is worse than
-- leaving them settleable.
ALTER TABLE settlement_configs
    ADD COLUMN payout_destination VARCHAR(80);

-- Below this, cutting a batch costs more in provider fees than it moves. NOT
-- NULL with a default of 1, which is "any positive balance": a zero default
-- would be indistinguishable from no minimum and a NULL would make every
-- comparison in the batch job a special case.
ALTER TABLE settlement_configs
    ADD COLUMN minimum_payout_minor BIGINT NOT NULL DEFAULT 1;

ALTER TABLE settlement_configs
    ADD CONSTRAINT ck_settlement_configs_minimum_payout
        CHECK (minimum_payout_minor > 0);
