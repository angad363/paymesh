-- =============================================================================
-- V32: the two invariants SDD 17.6 states, and the accounts money moves through.
-- ADR-032.
--
-- Both invariants are in the database rather than in a service, for the reason
-- V15 gives at length: the application check is the error message, the
-- constraint is the guard.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- SDD 17.6 INVARIANT 1: A BATCH'S NET EQUALS THE SUM OF ITS ITEMS.
--
-- A DEFERRED constraint trigger, the same mechanism as tr_ledger_entries_balanced
-- and for the same reason: the batch header is written before its items, so an
-- immediate check would fire against a batch that has no items yet and refuse
-- every correct write. Deferring to COMMIT is what lets the check be about the
-- finished object.
--
-- ON BOTH TABLES, WHICH tr_ledger_entries_balanced DOES NOT NEED TO BE.
-- Entries can only be added to a journal, so checking on insert into
-- ledger_entries covers everything. Here the header carries the total, so a
-- batch written with the wrong net and no items at all has to be caught too --
-- and that write touches only settlement_batches.
-- -----------------------------------------------------------------------------
CREATE FUNCTION settlement_assert_batch_total() RETURNS TRIGGER AS $$
DECLARE
    batch_id     VARCHAR(40);
    declared_net BIGINT;
    item_total   BIGINT;
    item_count   INTEGER;
BEGIN
    -- One expression for both tables: settlement_batches names its own id in
    -- this column and settlement_items names its parent's.
    batch_id := NEW.settlement_batch_id;

    SELECT net_amount_minor INTO declared_net
      FROM settlement_batches
     WHERE settlement_batch_id = batch_id;

    -- The batch was deleted in the same transaction. Nothing to assert about a
    -- row that will not exist at COMMIT.
    IF declared_net IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT COALESCE(SUM(amount_minor), 0), COUNT(*)
      INTO item_total, item_count
      FROM settlement_items
     WHERE settlement_batch_id = batch_id;

    -- A batch with no items is not a batch of zero, it is a batch that forgot
    -- to say what it was made of -- and ck_settlement_batches_net already
    -- refuses a net of zero, so 0 = 0 can never wave one through here either.
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

CREATE CONSTRAINT TRIGGER tr_settlement_batches_total
    AFTER INSERT ON settlement_batches
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION settlement_assert_batch_total();

CREATE CONSTRAINT TRIGGER tr_settlement_items_total
    AFTER INSERT ON settlement_items
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION settlement_assert_batch_total();


-- -----------------------------------------------------------------------------
-- A CUT BATCH IS IMMUTABLE EXCEPT FOR ITS STATUS.
--
-- SDD 17.1 says "immutable once closed" and every batch here is cut closed. The
-- status has to move -- PENDING_PAYOUT to PAID or RETURNED is the whole
-- lifecycle -- but the amount, the merchant, the currency and the instant it
-- was cut are a statement about a moment that has passed.
--
-- Items have no updatable field at all, so they are refused outright.
-- -----------------------------------------------------------------------------
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

CREATE TRIGGER tr_settlement_batches_append_only
    BEFORE UPDATE OR DELETE ON settlement_batches
    FOR EACH ROW
    EXECUTE FUNCTION settlement_batches_are_append_only();

CREATE FUNCTION settlement_items_are_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Settlement items cannot be changed or deleted'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_settlement_items_immutable
    BEFORE UPDATE OR DELETE ON settlement_items
    FOR EACH ROW
    EXECUTE FUNCTION settlement_items_are_immutable();


-- -----------------------------------------------------------------------------
-- SDD 17.6 INVARIANT 2: FUNDS MOVE THROUGH SETTLEMENT_IN_TRANSIT, NEVER
-- STRAIGHT OUT OF AVAILABLE.
--
-- Two new account types, and TWO CONSTRAINTS NAME THEM, not one. V29 failed the
-- first time it ran for exactly this reason and left the lesson written down:
-- ck_ledger_accounts_owner says which types carry a merchant,
-- ck_ledger_accounts_type says which types exist at all.
--
--   SETTLEMENT_IN_TRANSIT -- a LIABILITY, merchant-owned. Still the merchant's
--     money; it has simply been committed to a batch and can no longer be
--     settled again. Cutting a batch debits available and credits this.
--
--   BANK_CASH -- an ASSET, platform-owned, one per currency. PayMesh's own
--     funds. A completed payout debits settlement-in-transit and credits this:
--     the liability is discharged and the cash is gone.
-- -----------------------------------------------------------------------------
ALTER TABLE ledger_accounts DROP CONSTRAINT ck_ledger_accounts_type;

ALTER TABLE ledger_accounts ADD CONSTRAINT ck_ledger_accounts_type CHECK (
    account_type IN (
        'MERCHANT_PENDING',
        'MERCHANT_AVAILABLE',
        'SETTLEMENT_IN_TRANSIT',
        'PROVIDER_CLEARING',
        'BANK_CASH'
    )
);

ALTER TABLE ledger_accounts DROP CONSTRAINT ck_ledger_accounts_owner;

ALTER TABLE ledger_accounts ADD CONSTRAINT ck_ledger_accounts_owner CHECK (
    (account_type = 'MERCHANT_PENDING'      AND merchant_id IS NOT NULL)
    OR
    (account_type = 'MERCHANT_AVAILABLE'    AND merchant_id IS NOT NULL)
    OR
    (account_type = 'SETTLEMENT_IN_TRANSIT' AND merchant_id IS NOT NULL)
    OR
    (account_type = 'PROVIDER_CLEARING'     AND merchant_id IS NULL)
    OR
    (account_type = 'BANK_CASH'             AND merchant_id IS NULL)
);
