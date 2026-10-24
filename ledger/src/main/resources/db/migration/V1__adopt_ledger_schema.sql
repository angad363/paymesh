-- ============================================================================
--  V1__adopt_ledger_schema.sql
--  The ledger service's own tables: ledger_accounts, ledger_transactions,
--  ledger_entries (ADR-038 schema-per-service, ADR-050). (`ledger.merchant_ref`,
--  the V39 merchant-status projection, is NOT adopted -- the ledger reads no
--  other service's data and no code here touches it.)
--
--  THIS MODULE'S OWN FLYWAY HISTORY (ADR-041..049 recipe). These tables already
--  exist in the shared cluster's `ledger` schema: the monolith's V15 (create),
--  V29 and V32 (the MERCHANT_AVAILABLE / SETTLEMENT_IN_TRANSIT / BANK_CASH
--  account types) and V26's identifier CHECKs, moved into this schema by V38.
--  This file is that live shape (reconstructed from a pg_dump of the live
--  `ledger` schema), so a FRESH database gets the same schema. Against the
--  EXISTING shared dev database, `baseline-on-migrate` makes Flyway ADOPT the
--  present tables at this version and this file is not re-run.
--
--  THE INVARIANTS COME ACROSS WHOLE AND UNWEAKENED (ADR-050 section 1): the
--  deferred debits = credits trigger and the entry/transaction immutability
--  triggers live INSIDE the `ledger` schema here. (In the live monolith database
--  their FUNCTIONS sit in `public`, where V15 created them before V38 moved the
--  tables; a fresh database gets them in `ledger`. Bodies are byte-for-byte the
--  live ones.) NO FOREIGN KEY CROSSES A SCHEMA.
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


CREATE TABLE ledger_accounts (
    ledger_account_id VARCHAR(40)              NOT NULL,
    account_reference VARCHAR(120)             NOT NULL,
    merchant_id       VARCHAR(40),
    account_type      VARCHAR(32)              NOT NULL,
    currency          CHAR(3)                  NOT NULL,
    normal_balance    VARCHAR(6)               NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_ledger_accounts PRIMARY KEY (ledger_account_id),
    CONSTRAINT uq_ledger_accounts_reference UNIQUE (account_reference),
    CONSTRAINT uq_ledger_accounts_id_currency UNIQUE (ledger_account_id, currency),
    CONSTRAINT ck_ledger_accounts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_ledger_accounts_id_format CHECK (is_prefixed_id(ledger_account_id, 'lac_')),
    CONSTRAINT ck_ledger_accounts_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_ledger_accounts_normal_balance CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_accounts_type CHECK (account_type IN (
        'MERCHANT_PENDING', 'MERCHANT_AVAILABLE', 'SETTLEMENT_IN_TRANSIT',
        'PROVIDER_CLEARING', 'BANK_CASH')),
    CONSTRAINT ck_ledger_accounts_owner CHECK (
        (account_type = 'MERCHANT_PENDING'      AND merchant_id IS NOT NULL)
        OR (account_type = 'MERCHANT_AVAILABLE'     AND merchant_id IS NOT NULL)
        OR (account_type = 'SETTLEMENT_IN_TRANSIT'  AND merchant_id IS NOT NULL)
        OR (account_type = 'PROVIDER_CLEARING'      AND merchant_id IS NULL)
        OR (account_type = 'BANK_CASH'              AND merchant_id IS NULL))
);

CREATE INDEX ix_ledger_accounts_merchant ON ledger_accounts (merchant_id, account_type, currency)
    WHERE merchant_id IS NOT NULL;


CREATE TABLE ledger_transactions (
    ledger_transaction_id VARCHAR(40)              NOT NULL,
    merchant_id           VARCHAR(40)              NOT NULL,
    transaction_type      VARCHAR(40)              NOT NULL,
    reference_type        VARCHAR(32)              NOT NULL,
    reference_id          VARCHAR(40)              NOT NULL,
    currency              CHAR(3)                  NOT NULL,
    idempotency_key       VARCHAR(120)             NOT NULL,
    occurred_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_ledger_transactions PRIMARY KEY (ledger_transaction_id),
    CONSTRAINT uq_ledger_transactions_id_currency UNIQUE (ledger_transaction_id, currency),
    CONSTRAINT uq_ledger_transactions_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_ledger_transactions_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_ledger_transactions_id_format CHECK (is_prefixed_id(ledger_transaction_id, 'ltx_')),
    CONSTRAINT ck_ledger_transactions_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);

CREATE INDEX ix_ledger_transactions_merchant ON ledger_transactions (merchant_id, occurred_at DESC);
CREATE INDEX ix_ledger_transactions_reference ON ledger_transactions (reference_type, reference_id);


CREATE TABLE ledger_entries (
    ledger_entry_id       BIGSERIAL                NOT NULL,
    ledger_transaction_id VARCHAR(40)              NOT NULL,
    ledger_account_id     VARCHAR(40)              NOT NULL,
    direction             VARCHAR(6)               NOT NULL,
    amount_minor          BIGINT                   NOT NULL,
    currency              CHAR(3)                  NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_ledger_entries PRIMARY KEY (ledger_entry_id),
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (ledger_account_id, currency)
        REFERENCES ledger_accounts (ledger_account_id, currency),
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (ledger_transaction_id, currency)
        REFERENCES ledger_transactions (ledger_transaction_id, currency),
    CONSTRAINT ck_ledger_entries_account_id_format CHECK (is_prefixed_id(ledger_account_id, 'lac_')),
    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_ledger_entries_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_transaction_id_format CHECK (is_prefixed_id(ledger_transaction_id, 'ltx_'))
);

CREATE INDEX ix_ledger_entries_account ON ledger_entries (ledger_account_id, direction, amount_minor);
CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (ledger_transaction_id);


-- ----------------------------------------------------------------------------
--  Invariants (V15/V18), inside the `ledger` schema. UNCHANGED.
--  Unqualified table names in the bodies resolve through the search_path
--  (ledger, public) that Flyway and the pool both set.
-- ----------------------------------------------------------------------------

CREATE FUNCTION ledger_transactions_are_immutable()
 RETURNS trigger
 LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'Ledger transactions are immutable; correct a posting with a reversal transaction'
        USING ERRCODE = 'restrict_violation';
END;
$$
;
CREATE FUNCTION ledger_entries_are_immutable()
 RETURNS trigger
 LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'Ledger entries are immutable; correct a posting with a reversal transaction'
        USING ERRCODE = 'restrict_violation';
END;
$$
;
CREATE FUNCTION ledger_assert_balanced()
 RETURNS trigger
 LANGUAGE plpgsql
AS $$
DECLARE
    total_debits  BIGINT;
    total_credits BIGINT;
    entry_count   INTEGER;
    foreign_owner VARCHAR(40);
BEGIN
    -- THE JOURNAL MUST BE ABOUT THE MERCHANT WHOSE MONEY IT MOVES.
    --
    -- Nothing above this line ties ledger_transactions.merchant_id to the owner
    -- of the accounts its entries touch, and the composite tenant foreign keys
    -- that do this job in V5/V6/V8 cannot do it here: platform accounts carry a
    -- NULL merchant_id, and a composite key containing a NULL matches nothing.
    --
    -- Without this, a journal headed "merchant B" can credit merchant A's
    -- pending account and every other constraint passes. The money lands on A,
    -- because the balance query attributes by the ACCOUNT's owner -- so the
    -- balance is right and the audit header is a lie. "Everything posted for
    -- this merchant" then returns a set that does not reconcile against that
    -- merchant's own balance, which is the question the header column exists to
    -- answer.
    --
    -- Unreachable through the application: PostPaymentCapturedService derives
    -- the header merchant and both account references from a single MerchantId.
    -- It is here for the reason every other rule in this file is here -- the
    -- application check is the error message, the constraint is the guard.
    SELECT a.merchant_id
      INTO foreign_owner
      FROM ledger_entries e
      JOIN ledger_accounts a     ON a.ledger_account_id = e.ledger_account_id
      JOIN ledger_transactions t ON t.ledger_transaction_id = e.ledger_transaction_id
     WHERE e.ledger_transaction_id = NEW.ledger_transaction_id
       AND a.merchant_id IS NOT NULL
       AND a.merchant_id <> t.merchant_id
     LIMIT 1;

    IF foreign_owner IS NOT NULL THEN
        RAISE EXCEPTION
            'Ledger transaction % is headed for one merchant but moves %s money',
            NEW.ledger_transaction_id, foreign_owner
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT
        COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
        COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0),
        COUNT(*)
    INTO total_debits, total_credits, entry_count
    FROM ledger_entries
    WHERE ledger_transaction_id = NEW.ledger_transaction_id;

    -- A journal with no entries at all is not a balanced journal, it is an
    -- empty one -- 0 = 0 would otherwise wave it through. Two is the minimum
    -- that can balance with positive amounts on both sides.
    IF entry_count < 2 THEN
        RAISE EXCEPTION
            'Ledger transaction % has % entries; a journal needs at least two',
            NEW.ledger_transaction_id, entry_count
            USING ERRCODE = 'check_violation';
    END IF;

    IF total_debits <> total_credits THEN
        RAISE EXCEPTION
            'Ledger transaction % is unbalanced: debits % <> credits %',
            NEW.ledger_transaction_id, total_debits, total_credits
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$
;


CREATE CONSTRAINT TRIGGER tr_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_balanced();

CREATE TRIGGER tr_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_are_immutable();

CREATE TRIGGER tr_ledger_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_transactions_are_immutable();
