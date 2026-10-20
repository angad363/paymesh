-- ============================================================================
--  V1__adopt_settlement_schema.sql
--  The settlement service's own tables: settlement_configs, settlement_batches,
--  settlement_items, payouts, payout_callbacks (ADR-038 schema-per-service,
--  ADR-047). (provider_payouts is the simulator's table and is NOT settlement's;
--  the live `settlement.merchant_ref` read model is not adopted -- Settlement has
--  no merchant-status gate, ADR-047 section 6.)
--
--  THIS MODULE'S OWN FLYWAY HISTORY (ADR-041..046 recipe). These tables already
--  exist in the shared cluster's `settlement` schema: the monolith's V29, V30,
--  V31 (payouts, payout_callbacks), V32 (constraints + triggers) and V26's
--  identifier CHECKs, moved into this schema by V38. This file is that live shape
--  (reconstructed from a pg_dump of the monolith's V1..V40 applied to a clean
--  database), so a FRESH database gets the same schema. Against the EXISTING shared
--  dev database, `baseline-on-migrate` makes Flyway ADOPT the present tables at
--  this version and this file is not re-run.
--
--  The deferred-total and immutability triggers (ADR-032) move with the tables
--  and live INSIDE the `settlement` schema here. NO FOREIGN KEY CROSSES A SCHEMA:
--  every FK below stays inside `settlement`.
-- ============================================================================

CREATE OR REPLACE FUNCTION public.is_prefixed_id(value text, prefix text)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
    PARALLEL SAFE
AS $$
SELECT value ~ ('^' || prefix ||
                '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
$$;


CREATE TABLE settlement_configs (
    merchant_id            VARCHAR(40)              NOT NULL,
    holding_period_seconds INTEGER                  NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    payout_destination     VARCHAR(80),
    minimum_payout_minor   BIGINT                   NOT NULL DEFAULT 1,

    CONSTRAINT pk_settlement_configs PRIMARY KEY (merchant_id),
    CONSTRAINT ck_settlement_configs_holding_period CHECK (holding_period_seconds >= 0),
    CONSTRAINT ck_settlement_configs_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_settlement_configs_minimum_payout CHECK (minimum_payout_minor > 0)
);


CREATE TABLE settlement_batches (
    settlement_batch_id VARCHAR(40)              NOT NULL,
    merchant_id         VARCHAR(40)              NOT NULL,
    currency            CHAR(3)                  NOT NULL,
    net_amount_minor    BIGINT                   NOT NULL,
    cut_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_settlement_batches PRIMARY KEY (settlement_batch_id),
    CONSTRAINT uq_settlement_batches_batch_currency UNIQUE (settlement_batch_id, currency),
    CONSTRAINT uq_settlement_batches_merchant_batch UNIQUE (settlement_batch_id, merchant_id),
    CONSTRAINT ck_settlement_batches_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_settlement_batches_id_format CHECK (is_prefixed_id(settlement_batch_id, 'stl_')),
    CONSTRAINT ck_settlement_batches_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_settlement_batches_net CHECK (net_amount_minor > 0),
    CONSTRAINT ck_settlement_batches_status
        CHECK (status IN ('PENDING_PAYOUT', 'PAID', 'RETURNED'))
);

CREATE INDEX ix_settlement_batches_merchant_cut ON settlement_batches (merchant_id, cut_at DESC);


CREATE TABLE settlement_items (
    settlement_item_id  VARCHAR(40)              NOT NULL,
    settlement_batch_id VARCHAR(40)              NOT NULL,
    merchant_id         VARCHAR(40)              NOT NULL,
    currency            CHAR(3)                  NOT NULL,
    payment_intent_id   VARCHAR(40)              NOT NULL,
    amount_minor        BIGINT                   NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_settlement_items PRIMARY KEY (settlement_item_id),
    CONSTRAINT uq_settlement_items_batch_payment UNIQUE (settlement_batch_id, payment_intent_id),
    CONSTRAINT fk_settlement_items_batch FOREIGN KEY (settlement_batch_id)
        REFERENCES settlement_batches (settlement_batch_id),
    CONSTRAINT fk_settlement_items_batch_currency FOREIGN KEY (settlement_batch_id, currency)
        REFERENCES settlement_batches (settlement_batch_id, currency),
    CONSTRAINT fk_settlement_items_batch_merchant FOREIGN KEY (settlement_batch_id, merchant_id)
        REFERENCES settlement_batches (settlement_batch_id, merchant_id),
    CONSTRAINT ck_settlement_items_amount CHECK (amount_minor <> 0),
    CONSTRAINT ck_settlement_items_id_format CHECK (is_prefixed_id(settlement_item_id, 'sti_')),
    CONSTRAINT ck_settlement_items_payment_id_format CHECK (is_prefixed_id(payment_intent_id, 'pi_'))
);

CREATE INDEX ix_settlement_items_batch ON settlement_items (settlement_batch_id);


CREATE TABLE payouts (
    payout_id           VARCHAR(40)              NOT NULL,
    settlement_batch_id VARCHAR(40)              NOT NULL,
    merchant_id         VARCHAR(40)              NOT NULL,
    amount_minor        BIGINT                   NOT NULL,
    currency            CHAR(3)                  NOT NULL,
    destination         VARCHAR(80)              NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    attempts            INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error          VARCHAR(500),
    provider_reference  VARCHAR(60),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_payouts PRIMARY KEY (payout_id),
    CONSTRAINT uq_payouts_batch UNIQUE (settlement_batch_id),
    CONSTRAINT fk_payouts_batch FOREIGN KEY (settlement_batch_id)
        REFERENCES settlement_batches (settlement_batch_id),
    CONSTRAINT fk_payouts_batch_merchant FOREIGN KEY (settlement_batch_id, merchant_id)
        REFERENCES settlement_batches (settlement_batch_id, merchant_id),
    CONSTRAINT ck_payouts_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_payouts_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_payouts_batch_id_format CHECK (is_prefixed_id(settlement_batch_id, 'stl_')),
    CONSTRAINT ck_payouts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payouts_id_format CHECK (is_prefixed_id(payout_id, 'po_')),
    CONSTRAINT ck_payouts_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_payouts_status CHECK (status IN ('PENDING', 'SUBMITTED', 'PAID', 'FAILED'))
);

CREATE INDEX ix_payouts_due ON payouts (next_attempt_at) WHERE status IN ('PENDING', 'SUBMITTED');


CREATE TABLE payout_callbacks (
    provider          VARCHAR(40)              NOT NULL,
    external_event_id VARCHAR(100)             NOT NULL,
    payout_id         VARCHAR(40)              NOT NULL,
    outcome           VARCHAR(20)              NOT NULL,
    payload_hash      CHAR(64)                 NOT NULL,
    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at       TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_payout_callbacks PRIMARY KEY (provider, external_event_id),
    CONSTRAINT fk_payout_callbacks_payout FOREIGN KEY (payout_id) REFERENCES payouts (payout_id),
    CONSTRAINT ck_payout_callbacks_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED'))
);


-- ----------------------------------------------------------------------------
--  Invariants (ADR-032), inside the `settlement` schema.
--  Unqualified table names in the bodies resolve through the search_path
--  (settlement, public) that Flyway and the pool both set.
-- ----------------------------------------------------------------------------

-- A batch's declared net must equal the sum of its items, checked at COMMIT so the batch and its
-- items can be inserted in either order inside one transaction.
CREATE FUNCTION settlement_assert_batch_total() RETURNS TRIGGER AS $$
DECLARE
    batch_id     VARCHAR(40);
    declared_net BIGINT;
    item_total   BIGINT;
    item_count   INTEGER;
BEGIN
    -- One expression for both tables: settlement_batches names its own id in this column and
    -- settlement_items names its parent's.
    batch_id := NEW.settlement_batch_id;

    SELECT net_amount_minor INTO declared_net
      FROM settlement_batches
     WHERE settlement_batch_id = batch_id;

    IF declared_net IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT COALESCE(SUM(amount_minor), 0), COUNT(*)
      INTO item_total, item_count
      FROM settlement_items
     WHERE settlement_batch_id = batch_id;

    IF item_count = 0 THEN
        RAISE EXCEPTION
            'Settlement batch % has no items',
            batch_id
            USING ERRCODE = 'check_violation';
    END IF;

    IF declared_net <> item_total THEN
        RAISE EXCEPTION
            'Settlement batch % declares net % but its items sum to %',
            batch_id, declared_net, item_total
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION settlement_batches_are_append_only() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Settlement batches cannot be deleted'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.merchant_id         <> OLD.merchant_id
        OR NEW.currency        <> OLD.currency
        OR NEW.net_amount_minor <> OLD.net_amount_minor
        OR NEW.cut_at          <> OLD.cut_at
        OR NEW.created_at      <> OLD.created_at
    THEN
        RAISE EXCEPTION
            'Settlement batch % is immutable except for its status',
            OLD.settlement_batch_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION settlement_items_are_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Settlement items cannot be changed or deleted'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER tr_settlement_batches_total
    AFTER INSERT ON settlement_batches
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION settlement_assert_batch_total();

CREATE CONSTRAINT TRIGGER tr_settlement_items_total
    AFTER INSERT ON settlement_items
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION settlement_assert_batch_total();

CREATE TRIGGER tr_settlement_batches_append_only
    BEFORE UPDATE OR DELETE ON settlement_batches
    FOR EACH ROW EXECUTE FUNCTION settlement_batches_are_append_only();

CREATE TRIGGER tr_settlement_items_immutable
    BEFORE UPDATE OR DELETE ON settlement_items
    FOR EACH ROW EXECUTE FUNCTION settlement_items_are_immutable();
