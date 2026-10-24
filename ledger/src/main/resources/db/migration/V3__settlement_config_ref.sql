-- ============================================================================
--  V3__settlement_config_ref.sql
--  ADR-051: the ledger's local read model of each merchant's holding period,
--  fed by `settlement.config.updated` (the ADR-039 / ADR-048 projection shape).
--  `ledger_svc` has no grant on the settlement schema, so this is how the
--  release job learns the per-merchant period.
--
--  A merchant with NO row gets the platform default AS A VALUE (never a stored
--  row), so a later change to the default still reaches every defaulted merchant.
--  No FK to any merchant table (a sibling schema); the id-format CHECK is kept.
-- ============================================================================
CREATE TABLE settlement_config_ref (
    merchant_id            VARCHAR(40)              NOT NULL,
    holding_period_seconds INTEGER                  NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_settlement_config_ref PRIMARY KEY (merchant_id),

    CONSTRAINT ck_settlement_config_ref_holding_period CHECK (holding_period_seconds >= 0),

    CONSTRAINT ck_settlement_config_ref_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);
