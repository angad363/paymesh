-- =============================================================================
-- V29: money can now become settleable. SDD 15.1 / 17.4, ADR-031.
--
-- Two things, and neither is a new ledger table.
--
--   1. MERCHANT_AVAILABLE joins the chart of accounts.
--   2. settlement_configs holds the one setting that decides when funds move
--      into it.
--
-- WHAT IS DELIBERATELY NOT HERE
--
-- The phase-2 plan called for a second migration holding "release job state".
-- There is none, because the ledger already answers the only question that job
-- asks. Every transaction carries a unique idempotency_key
-- (uq_ledger_transactions_idempotency, V15), so "has this payment been
-- released?" is "does a row with key funds-released:<paymentIntentId> exist?".
-- A state table would be a second copy of a fact the ledger already holds, and
-- the failure mode of a second copy is that it disagrees with the first --
-- which is the same argument BalanceRepository makes for summing entries
-- rather than projecting them (ADR-018 section 5).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- MERCHANT_AVAILABLE, a CREDIT-normal liability exactly like MERCHANT_PENDING.
--
-- Both are money PayMesh owes the merchant. The difference is not accounting,
-- it is permission: pending is owed but not yet withdrawable, available is owed
-- and settleable. A release moves value between two liabilities of the same
-- merchant, so it nets to zero against the platform's own position -- which is
-- why it is a balanced transaction rather than an adjustment.
--
-- AccountType's javadoc named this constant as deliberately absent because
-- "MERCHANT_AVAILABLE needs a settlement schedule to move money out of
-- pending". This migration is that schedule arriving.
-- -----------------------------------------------------------------------------
-- TWO constraints name the account types, not one, and missing the second is
-- how this migration failed the first time it ran: ck_ledger_accounts_owner
-- says which types carry a merchant, and ck_ledger_accounts_type says which
-- types exist at all. A new type has to be admitted by both.
ALTER TABLE ledger_accounts DROP CONSTRAINT ck_ledger_accounts_type;

ALTER TABLE ledger_accounts ADD CONSTRAINT ck_ledger_accounts_type CHECK (
    account_type IN ('MERCHANT_PENDING', 'MERCHANT_AVAILABLE', 'PROVIDER_CLEARING')
);

ALTER TABLE ledger_accounts DROP CONSTRAINT ck_ledger_accounts_owner;

ALTER TABLE ledger_accounts ADD CONSTRAINT ck_ledger_accounts_owner CHECK (
    (account_type = 'MERCHANT_PENDING'   AND merchant_id IS NOT NULL)
    OR
    (account_type = 'MERCHANT_AVAILABLE' AND merchant_id IS NOT NULL)
    OR
    (account_type = 'PROVIDER_CLEARING'  AND merchant_id IS NULL)
);


-- -----------------------------------------------------------------------------
-- ONE COLUMN OF SETTINGS, NOT THE FIVE SDD 17.4 SPECIFIES.
--
-- The SDD's settlement_configs carries a schedule, a minimum payout amount, a
-- currency and a payout account alongside the holding period. Four of those are
-- Settlement's (PR 4) and have no reader here: a schedule nothing runs to, a
-- minimum nothing compares against, a payout account nothing pays into. A
-- column whose semantics are decided by a capability that does not exist is a
-- column that gets them wrong.
--
-- The holding period is different. It is the input to the release job in this
-- migration, so it lands with the thing that reads it. PR 4 adds the rest as an
-- ordinary ALTER, against a table whose meaning is by then settled.
-- -----------------------------------------------------------------------------
CREATE TABLE settlement_configs (
    -- The merchant IS the key. One config per merchant, so there is no separate
    -- surrogate id to expose and no way to write a second row that silently
    -- shadows the first.
    merchant_id    VARCHAR(40)              NOT NULL,

    -- How long captured funds stay pending before they may be released.
    --
    -- SECONDS, as an integer, rather than a PostgreSQL INTERVAL. An INTERVAL can
    -- express "1 month", which has no fixed length, and a holding period whose
    -- duration depends on which month it is asked in cannot be reasoned about by
    -- a merchant or reproduced by a test. Java reads this straight into a
    -- Duration.
    holding_period_seconds INTEGER          NOT NULL,

    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_settlement_configs PRIMARY KEY (merchant_id),

    CONSTRAINT fk_settlement_configs_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    -- ZERO IS ALLOWED AND NEGATIVE IS NOT.
    --
    -- Zero means "release as soon as the job runs", which is a legitimate
    -- setting for a trusted merchant and the easiest way to demonstrate the
    -- release path. A negative period would mean funds were releasable before
    -- they were captured, which is not a policy, it is a sign error.
    CONSTRAINT ck_settlement_configs_holding_period
        CHECK (holding_period_seconds >= 0),

    CONSTRAINT ck_settlement_configs_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);
