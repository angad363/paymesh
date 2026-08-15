-- =============================================================================
-- V31: the money actually leaves. SDD 17.2, 13.3-13.4, ADR-032.
--
-- Four things:
--
--   1. payouts             -- PayMesh's record of one batch being paid out.
--   2. payout_callbacks    -- the dedup table for the provider's answer, so a
--                             redelivered callback is a no-op.
--   3. provider_payouts    -- the SIMULATOR's side, SDD 13.4, which had no
--                             consumer until this migration.
--   4. the columns that let the simulator's existing callback queue carry a
--      payout callback as well as a payment one.
--
-- Point 4 is the one the project-status open item predicted: "closing it means
-- a target URL on provider_outbound_callbacks, a body writer, and a migration".
-- This is that migration, and the queue, the dispatcher, the signing and the
-- retry budget are all reused rather than rebuilt.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- ONE PAYOUT PER BATCH, AND THE UNIQUE CONSTRAINT IS WHAT MAKES IT TRUE.
--
-- The batch is the amount; the payout is the attempt to move it. Keeping them
-- as two tables rather than four status columns on one is what lets a payout
-- fail terminally and return its funds while the batch that described them
-- stays exactly as it was written -- the ledger's own rule about corrections,
-- applied one layer up.
-- -----------------------------------------------------------------------------
CREATE TABLE payouts (
    payout_id             VARCHAR(40)              NOT NULL,

    settlement_batch_id   VARCHAR(40)              NOT NULL,

    merchant_id           VARCHAR(40)              NOT NULL,

    amount_minor          BIGINT                   NOT NULL,
    currency              CHAR(3)                  NOT NULL,

    -- Copied from settlement_configs at submission time, not read through at
    -- send time. A merchant changing their bank details must not silently
    -- re-aim a payout already in flight, and an operator asking "where did this
    -- money go?" needs the answer the row was created with.
    destination           VARCHAR(80)              NOT NULL,

    status                VARCHAR(20)              NOT NULL,

    -- THE RETRY BUDGET, IN THE SHAPE ADR-025 SET: bounded attempts, a terminal
    -- state, and a log line -- not infinite retry. next_attempt_at is both the
    -- backoff and the claim clock, exactly as deliver_after is for the
    -- simulator's queue.
    attempts              INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMP WITH TIME ZONE NOT NULL,

    -- NO payout_attempts TABLE, deliberately, and this is the fourth time this
    -- codebase has declined one (refund_attempts, webhook_delivery_attempts,
    -- and the outbox's per-attempt history). The counter answers "how hard has
    -- this tried?" and the last error answers "why is it failing?", which is
    -- what an operator debugging a stuck payout asks. A row per attempt is a
    -- log wearing a table's clothes, and it can be added later without touching
    -- an invariant.
    last_error            VARCHAR(500),

    -- The provider's own id for this payout, once it has one. Null until
    -- submitted, and the thing to quote at the provider when a payout is
    -- disputed.
    provider_reference    VARCHAR(60),

    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_payouts PRIMARY KEY (payout_id),

    -- ONE LIVE PAYOUT PER BATCH, AND NOT AS A PARTIAL INDEX.
    --
    -- Unlike a payment intent (V8), a batch that fails terminally does not get
    -- a second payout: its funds go back to available and the next batch picks
    -- them up. So "one payout per batch" is total, not "one live one", and a
    -- plain unique constraint says that more clearly than a partial index would.
    CONSTRAINT uq_payouts_batch UNIQUE (settlement_batch_id),

    CONSTRAINT fk_payouts_batch FOREIGN KEY (settlement_batch_id)
        REFERENCES settlement_batches (settlement_batch_id),

    -- Composite, so a payout cannot name one merchant while paying out another
    -- merchant's batch.
    CONSTRAINT fk_payouts_batch_merchant
        FOREIGN KEY (settlement_batch_id, merchant_id)
        REFERENCES settlement_batches (settlement_batch_id, merchant_id),

    -- PENDING: created, not yet submitted. SUBMITTED: the provider has it and
    -- the answer is a callback away. PAID and FAILED are terminal.
    --
    -- There is no CANCELLED. A merchant cannot cancel a payout of their own
    -- money to their own account, and PayMesh cancelling one after submission
    -- would be an opinion about a bank movement it cannot see -- the same
    -- argument ADR-019 makes for refund cancellation answering 409.
    CONSTRAINT ck_payouts_status CHECK (status IN (
        'PENDING', 'SUBMITTED', 'PAID', 'FAILED'
    )),

    CONSTRAINT ck_payouts_amount CHECK (amount_minor > 0),

    CONSTRAINT ck_payouts_attempts CHECK (attempts >= 0),

    CONSTRAINT ck_payouts_currency CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_payouts_id_format CHECK (is_prefixed_id(payout_id, 'po_')),

    CONSTRAINT ck_payouts_batch_id_format
        CHECK (is_prefixed_id(settlement_batch_id, 'stl_')),

    CONSTRAINT ck_payouts_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);

-- The submission sweep's claim query: due, not terminal, oldest first.
CREATE INDEX ix_payouts_due ON payouts (next_attempt_at)
    WHERE status IN ('PENDING', 'SUBMITTED');


-- -----------------------------------------------------------------------------
-- THE DEDUP TABLE, AND IT IS THE SAME SHAPE AS refund_callbacks (V16).
--
-- Payout has its own route and therefore its own dedup table, for the reason
-- ADR-019 gives: sharing Payment's would mean Payment knowing payouts exist in
-- order to route the callback. The primary key IS the dedup -- an insert that
-- collides is a redelivery, and the row count is the answer, so there is no
-- read-then-write window.
-- -----------------------------------------------------------------------------
CREATE TABLE payout_callbacks (
    provider          VARCHAR(40)              NOT NULL,
    external_event_id VARCHAR(100)             NOT NULL,

    payout_id         VARCHAR(40)              NOT NULL,

    outcome           VARCHAR(20)              NOT NULL,

    -- The hash of the exact bytes the signature covered. Recorded so a
    -- redelivery that differs from what was applied is visible rather than
    -- silently absorbed.
    payload_hash      CHAR(64)                 NOT NULL,

    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at       TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_payout_callbacks PRIMARY KEY (provider, external_event_id),

    CONSTRAINT fk_payout_callbacks_payout FOREIGN KEY (payout_id)
        REFERENCES payouts (payout_id),

    CONSTRAINT ck_payout_callbacks_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED'))
);


-- -----------------------------------------------------------------------------
-- THE SIMULATOR'S SIDE. SDD 13.4, which V13 named as absent because Settlement
-- did not exist to consume it.
--
-- Deterministic on the DESTINATION, exactly as payments are deterministic on
-- the token: acct_sim_success pays, acct_sim_fail is refused by the bank.
-- Anything else pays, so a merchant configured with a realistic-looking account
-- number still demonstrates the happy path.
-- -----------------------------------------------------------------------------
CREATE TABLE provider_payouts (
    provider_payout_id  VARCHAR(60)              NOT NULL,

    -- PayMesh's payout id, and THE IDEMPOTENCY KEY OF THIS TABLE. A resubmitted
    -- payout must return the row it created the first time rather than moving
    -- the money twice -- the same rule POST /sim/v1/payments follows, and the
    -- reason a retry of a submission is safe.
    external_reference  VARCHAR(60)              NOT NULL,

    destination         VARCHAR(80)              NOT NULL,

    amount_minor        BIGINT                   NOT NULL,
    currency            CHAR(3)                  NOT NULL,

    status              VARCHAR(20)              NOT NULL,

    failure_code        VARCHAR(40),

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_provider_payouts PRIMARY KEY (provider_payout_id),

    CONSTRAINT uq_provider_payouts_external_reference UNIQUE (external_reference),

    -- The provider's own vocabulary, NOT PayMesh's. PAID and RETURNED are what
    -- a bank says; the translation into PayMesh's PAID/FAILED happens at the
    -- callback boundary, which is the only place the two dictionaries meet.
    CONSTRAINT ck_provider_payouts_status CHECK (status IN ('PAID', 'RETURNED')),

    CONSTRAINT ck_provider_payouts_amount CHECK (amount_minor > 0),

    CONSTRAINT ck_provider_payouts_currency CHECK (currency ~ '^[A-Z]{3}$'),

    -- ADR-029: the database refuses an identifier the application could not
    -- read back. sim_po_, beside the simulator's sim_pay_ and sim_ref_.
    CONSTRAINT ck_provider_payouts_id_format
        CHECK (is_prefixed_id(provider_payout_id, 'sim_po_')),

    CONSTRAINT ck_provider_payouts_external_reference_format
        CHECK (is_prefixed_id(external_reference, 'po_'))
);


-- -----------------------------------------------------------------------------
-- ONE QUEUE, TWO KINDS OF CALLBACK.
--
-- The alternative was a second queue table for payout callbacks, which would
-- have duplicated the dispatcher, the retry budget, the signing and the
-- stored-bytes rule -- four things that are only correct once each. So the
-- existing queue grows a target instead.
--
-- provider_payment_id becomes NULLABLE, which is the one thing to be careful
-- about: it was the guarantee that every queued callback named a payment. The
-- XOR check below replaces that guarantee with a stronger one -- every row
-- names EXACTLY ONE of a payment or a payout, never both and never neither.
-- -----------------------------------------------------------------------------
ALTER TABLE provider_outbound_callbacks
    ALTER COLUMN provider_payment_id DROP NOT NULL;

ALTER TABLE provider_outbound_callbacks
    ADD COLUMN provider_payout_id VARCHAR(60);

-- WHERE TO POST IT. PAYMENT goes to the payment callback route, PAYOUT to the
-- payout one. A column rather than a lookup from the kind, because the
-- dispatcher must not have to know the shape of a row to know where it goes.
ALTER TABLE provider_outbound_callbacks
    ADD COLUMN callback_target VARCHAR(20) NOT NULL DEFAULT 'PAYMENT';

ALTER TABLE provider_outbound_callbacks
    ADD CONSTRAINT fk_provider_outbound_callbacks_payout
        FOREIGN KEY (provider_payout_id)
        REFERENCES provider_payouts (provider_payout_id);

ALTER TABLE provider_outbound_callbacks
    ADD CONSTRAINT ck_provider_outbound_callbacks_target
        CHECK (callback_target IN ('PAYMENT', 'PAYOUT'));

ALTER TABLE provider_outbound_callbacks
    ADD CONSTRAINT ck_provider_outbound_callbacks_payout_id_format
        CHECK (is_prefixed_id(provider_payout_id, 'sim_po_'));

-- EXACTLY ONE SUBJECT. Not "at least one": a row naming both would be delivered
-- once and be two different events depending on which column the reader looked
-- at.
ALTER TABLE provider_outbound_callbacks
    ADD CONSTRAINT ck_provider_outbound_callbacks_subject CHECK (
        (callback_target = 'PAYMENT'
         AND provider_payment_id IS NOT NULL AND provider_payout_id IS NULL)
        OR
        (callback_target = 'PAYOUT'
         AND provider_payout_id IS NOT NULL AND provider_payment_id IS NULL)
    );
