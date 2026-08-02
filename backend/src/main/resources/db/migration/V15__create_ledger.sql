-- ============================================================================
--  V15__create_ledger.sql
--  The double-entry ledger. SDD section 15, and ADR-018.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  THIS IS THE FINANCIAL SOURCE OF TRUTH, and until this migration existed the
--  platform had none. A payment intent reaching SUCCEEDED was operational state
--  and nothing more: no balance moved anywhere in the codebase, because there
--  was nowhere for a balance to live. README said so in as many words. That is
--  what changes here.
--
--  THE ONE RULE THE WHOLE FILE SERVES: a posted transaction's debits equal its
--  credits, and a posted entry is never edited or deleted. Everything below is
--  either that rule or a consequence of it.
--
--  WHY SO MUCH OF IT IS A DATABASE CONSTRAINT RATHER THAN JAVA. Every other
--  capability here follows the same rule (README: "the pre-check exists only to
--  return a readable 409 or 422 instead of a constraint-violation 500 -- the
--  integration tests bypass it and still pass, because the constraint is the
--  guard"), and the ledger is the one place where a bypassed pre-check is not a
--  bad error message but an unauditable balance. So: the balance rule is a
--  deferred constraint trigger, immutability is a trigger, single-currency is a
--  composite foreign key, and positive amounts are a CHECK. An application bug,
--  a future migration, or somebody at a psql prompt cannot get around any of
--  them.
--
--  WHAT THIS MIGRATION DELIBERATELY DOES NOT CREATE, all of SDD 15.5:
--    * balance_holds -- nothing reserves funds until Settlement (Phase 2).
--    * account_balances -- the "fast authoritative projection". A projection can
--      drift from the entries it summarizes; a SUM over the entries cannot, and
--      is the same query the projection would have to be rebuilt from anyway.
--      Added when a balance read measures slow, not before. ADR-018 section 5.
--    * The fee-revenue account. There is no fee schedule anywhere in this
--      codebase -- no rate, no rounding rule, no effective dates -- so a posting
--      that splits out a fee would be inventing a product decision. The posting
--      is two entries, gross, and still balances. ADR-018 section 4.
-- ============================================================================

-- ----------------------------------------------------------------------------
--  ledger_accounts -- the chart of accounts (SDD 15.1)
-- ----------------------------------------------------------------------------
CREATE TABLE ledger_accounts (
    -- "lac_" + UUID (ADR-003). Always exactly 40 chars.
    ledger_account_id  VARCHAR(40)              NOT NULL,

    -- THE NATURAL KEY, and the reason this table has no composite unique over
    -- (owner, code, currency) as SDD 15.5 sketches. Platform accounts have no
    -- merchant, so that key would contain a NULL -- and in PostgreSQL two rows
    -- with a NULL in a UNIQUE constraint do not collide, which means the key
    -- would silently permit duplicate platform accounts. Duplicate accounts are
    -- how a ledger loses money: half the postings land in one, half in the
    -- other, and both balances are wrong with nothing anywhere reading as an
    -- error.
    --
    -- So the address IS the key, spelled exactly as SDD 15.4's posting contract
    -- addresses it: 'provider-clearing:INR', 'merchant:mrc_<uuid>:pending:INR'.
    -- One column, no NULL semantics to reason about, and it is the same string
    -- an operator would paste into a query.
    account_reference  VARCHAR(120)             NOT NULL,

    -- NULL for platform accounts, which belong to no tenant -- provider
    -- clearing is PayMesh's own receivable from the provider, not any
    -- merchant's. Set, and foreign-keyed, for merchant accounts.
    --
    -- This is the one table in the codebase where a nullable merchant_id is
    -- correct rather than a tenancy hole. The scoping guarantee is not weakened
    -- by it: GET /api/v1/balances filters on merchant_id = :caller AND
    -- account_type = 'MERCHANT_PENDING', so a platform account can never appear
    -- in a merchant's balance no matter what the caller sends.
    merchant_id        VARCHAR(40),

    -- MERCHANT_PENDING | PROVIDER_CLEARING. Two of SDD 15.1's nine, because
    -- these are the two a captured payment touches. MERCHANT_AVAILABLE and
    -- MERCHANT_RESERVED need a settlement schedule and holds respectively;
    -- BANK_CASH and SETTLEMENT_IN_TRANSIT need Settlement; PLATFORM_FEE_REVENUE
    -- needs a fee schedule; REFUND_RECEIVABLE needs Refund. Each arrives with
    -- the thing that posts to it.
    account_type       VARCHAR(32)              NOT NULL,

    currency           CHAR(3)                  NOT NULL,

    -- DEBIT | CREDIT. Which direction INCREASES this account, and it is stored
    -- rather than derived because it is the only thing that makes a balance
    -- readable without knowing accounting: provider clearing is an asset and
    -- grows on the debit side, a merchant's pending balance is money PayMesh
    -- owes them -- a liability -- and grows on the credit side. A balance query
    -- that got this backwards would report every merchant as owing PayMesh the
    -- exact amount PayMesh owes them, with every individual entry correct.
    normal_balance     VARCHAR(6)               NOT NULL,

    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_ledger_accounts PRIMARY KEY (ledger_account_id),

    CONSTRAINT uq_ledger_accounts_reference UNIQUE (account_reference),

    -- The target of ledger_entries' composite currency foreign key below. Never
    -- queried directly; it exists so that an entry cannot name an account in a
    -- currency that account is not denominated in.
    CONSTRAINT uq_ledger_accounts_id_currency UNIQUE (ledger_account_id, currency),

    CONSTRAINT fk_ledger_accounts_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    CONSTRAINT ck_ledger_accounts_type
        CHECK (account_type IN ('MERCHANT_PENDING', 'PROVIDER_CLEARING')),

    CONSTRAINT ck_ledger_accounts_normal_balance
        CHECK (normal_balance IN ('DEBIT', 'CREDIT')),

    CONSTRAINT ck_ledger_accounts_currency CHECK (currency ~ '^[A-Z]{3}$'),

    -- A MERCHANT ACCOUNT HAS A MERCHANT AND A PLATFORM ACCOUNT HAS NONE. Without
    -- this, a merchant-owned account row with a NULL merchant_id would simply
    -- never appear in any balance -- the money would be posted, the entries
    -- would balance, every constraint would pass, and the merchant's balance
    -- would silently read short. Failing at insert is the only version of that
    -- anybody would ever notice.
    CONSTRAINT ck_ledger_accounts_owner CHECK (
        (account_type = 'MERCHANT_PENDING'   AND merchant_id IS NOT NULL)
        OR
        (account_type = 'PROVIDER_CLEARING'  AND merchant_id IS NULL)
    )
);

-- Every balance read is "this merchant's accounts of this type". Without the
-- index that is a sequential scan of the whole chart of accounts, which grows
-- with the merchant count.
CREATE INDEX ix_ledger_accounts_merchant
    ON ledger_accounts (merchant_id, account_type, currency)
    WHERE merchant_id IS NOT NULL;

-- ----------------------------------------------------------------------------
--  ledger_transactions -- the journal header (SDD 15.5)
-- ----------------------------------------------------------------------------
CREATE TABLE ledger_transactions (
    -- "ltx_" + UUID (ADR-003).
    ledger_transaction_id VARCHAR(40)              NOT NULL,

    -- The merchant this journal is ABOUT. Denormalized from the merchant
    -- account its entries touch, and worth the duplication: "show me everything
    -- posted for this merchant" is the first question anybody asks during an
    -- incident, and without this column it is a join through entries to
    -- accounts with a DISTINCT on top.
    merchant_id           VARCHAR(40)              NOT NULL,

    -- PAYMENT_CAPTURED today, and the enumeration is left to the application
    -- rather than a CHECK: unlike account_type, where a wrong value silently
    -- corrupts a balance, a wrong transaction_type is a mislabelled but
    -- correctly-posted journal. Refunds and settlements will each add a name
    -- here, and a CHECK would make every one of them a migration.
    transaction_type      VARCHAR(40)              NOT NULL,

    -- What in the rest of the platform caused this posting -- PAYMENT_INTENT
    -- and a "pi_" id today. NOT a foreign key, deliberately: the ledger is the
    -- module most likely to be extracted into its own service and its own
    -- database (SDD 30.1 puts it last precisely because it is the most
    -- dangerous to move), and a foreign key into payment_intents would have to
    -- be dropped on the day it moves. It is a reference, and it is stored the
    -- way a reference across a service boundary is stored: by value.
    reference_type        VARCHAR(32)              NOT NULL,
    reference_id          VARCHAR(40)              NOT NULL,

    currency              CHAR(3)                  NOT NULL,

    -- THE SECOND GUARD AGAINST POSTING THE SAME MONEY TWICE, and it is not
    -- redundant with the first.
    --
    -- The first is the inbox: processed_events (V14) stops the SAME EVENT being
    -- applied twice by the same consumer, which is what at-least-once delivery
    -- guarantees will happen. This stops something the inbox structurally
    -- cannot see -- the same payment being posted from a DIFFERENT event, a
    -- replayed backlog after somebody renames a consumer, a manual re-run, or a
    -- second emitter added later. The key is derived from the PAYMENT
    -- ('payment-captured:pi_<uuid>', SDD 15.4's own example), not from the event
    -- id, and that is the whole point: two different events describing one
    -- capture collide here and the second is refused.
    --
    -- Choosing the payment over the event does mean a genuine second capture of
    -- one intent would be silently refused rather than posted. That is the
    -- correct direction to fail in a ledger, and it is currently unreachable
    -- anyway -- PaymentIntent allows one capture per intent. When partial
    -- captures become repeatable, this key gains the capture sequence, and the
    -- test that pins the key's shape is what will force the decision.
    idempotency_key       VARCHAR(120)             NOT NULL,

    -- When the authority that decided this says it happened -- the provider's
    -- clock, or the capture instant. Read from the event payload's occurredAt,
    -- the same field Order's consumer reads.
    occurred_at           TIMESTAMP WITH TIME ZONE NOT NULL,

    -- When PayMesh posted it. Genuinely different from occurred_at on a late
    -- delivery, and reconciliation needs both: one orders the financial
    -- timeline, the other explains why the ledger only learned about it now.
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_ledger_transactions PRIMARY KEY (ledger_transaction_id),

    CONSTRAINT uq_ledger_transactions_idempotency UNIQUE (idempotency_key),

    -- The target of ledger_entries' composite currency foreign key. See
    -- uq_ledger_accounts_id_currency above.
    CONSTRAINT uq_ledger_transactions_id_currency
        UNIQUE (ledger_transaction_id, currency),

    CONSTRAINT fk_ledger_transactions_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    CONSTRAINT ck_ledger_transactions_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_ledger_transactions_merchant
    ON ledger_transactions (merchant_id, occurred_at DESC);

CREATE INDEX ix_ledger_transactions_reference
    ON ledger_transactions (reference_type, reference_id);

-- ----------------------------------------------------------------------------
--  ledger_entries -- the immutable debit/credit lines (SDD 15.5)
-- ----------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    -- A BIGSERIAL, and the only sequential identifier exposed anywhere in this
    -- codebase's schema -- ADR-003's opaque prefixed ids are for things the API
    -- addresses, and nothing addresses an individual entry. The journal is the
    -- unit a caller names.
    ledger_entry_id       BIGSERIAL                NOT NULL,

    ledger_transaction_id VARCHAR(40)              NOT NULL,
    ledger_account_id     VARCHAR(40)              NOT NULL,

    -- DEBIT | CREDIT. Stored SEPARATELY from the amount, which is SDD 15.6's
    -- third invariant and the reason amount_minor can be CHECKed positive: a
    -- signed amount would make "debit 500" and "credit -500" two spellings of
    -- one fact, and every SUM in the system would have to know which spelling
    -- it was looking at.
    direction             VARCHAR(6)               NOT NULL,

    -- Minor units. Positive, always -- see direction above.
    amount_minor          BIGINT                   NOT NULL,

    -- Denormalized from both parents so the two composite foreign keys below
    -- can pin it. It is not free-floating data: it is structurally forced equal
    -- to the transaction's currency AND the account's currency, which is
    -- exactly SDD 15.6's second invariant ("all entries in the first version use
    -- the same currency") enforced rather than asserted.
    currency              CHAR(3)                  NOT NULL,

    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_ledger_entries PRIMARY KEY (ledger_entry_id),

    -- SINGLE-CURRENCY, ENFORCED BY THE SHAPE OF THE KEY rather than by a check
    -- somebody has to remember to write. This is the same trick the composite
    -- tenant foreign keys use (V5, V6, V8): make the wrong row impossible to
    -- insert instead of detecting it afterwards. An entry cannot name a
    -- transaction in one currency and an account in another, and it cannot name
    -- an account in a currency that account is not denominated in.
    CONSTRAINT fk_ledger_entries_transaction
        FOREIGN KEY (ledger_transaction_id, currency)
        REFERENCES ledger_transactions (ledger_transaction_id, currency),

    CONSTRAINT fk_ledger_entries_account
        FOREIGN KEY (ledger_account_id, currency)
        REFERENCES ledger_accounts (ledger_account_id, currency),

    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),

    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount_minor > 0),

    CONSTRAINT ck_ledger_entries_currency CHECK (currency ~ '^[A-Z]{3}$')
);

-- The balance query: every entry for a set of accounts. Covering the direction
-- and amount keeps it index-only.
CREATE INDEX ix_ledger_entries_account
    ON ledger_entries (ledger_account_id, direction, amount_minor);

CREATE INDEX ix_ledger_entries_transaction
    ON ledger_entries (ledger_transaction_id);

-- ----------------------------------------------------------------------------
--  SDD 15.6 invariant 1: debits equal credits -- and the journal belongs to
--  the merchant whose money it moves.
-- ----------------------------------------------------------------------------
--  A DEFERRED CONSTRAINT TRIGGER, and the deferral is the entire design.
--
--  A journal is balanced only once ALL of its entries exist. Checked per row it
--  would fail on the first INSERT of every transaction ever written, because one
--  debit on its own never balances. So the check runs once, at COMMIT, when the
--  statement that wrote the entries is finished and the transaction is about to
--  become durable -- the exact moment "posted" starts to mean something.
--
--  This is what makes the invariant unbypassable. The application also checks it
--  (LedgerTransaction refuses to construct an unbalanced journal, so the caller
--  gets a readable error rather than a constraint violation), but that check can
--  be skipped, refactored around, or bypassed by a direct INSERT. This one
--  cannot: it fires for the application, for a migration, and for a human at a
--  psql prompt, and the transaction does not commit.
--
--  A test proves it by inserting a deliberately lopsided journal with the
--  application layer entirely out of the path.
-- ----------------------------------------------------------------------------
CREATE FUNCTION ledger_assert_balanced() RETURNS TRIGGER AS $$
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
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER tr_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger_assert_balanced();

-- ----------------------------------------------------------------------------
--  SDD 15.6 invariant 5: posted entries cannot be updated or deleted.
-- ----------------------------------------------------------------------------
--  "Immutable" written in a comment is a hope. This is the same sentence as a
--  rule the database keeps.
--
--  A TRIGGER RATHER THAN "REVOKE UPDATE, DELETE". A revoke is one line and would
--  be the lazier answer, but it is granted per role: it protects against the
--  application's role and says nothing about a migration running as the owner,
--  and it would not be in force under Testcontainers, where the test connects as
--  the superuser. An invariant that is absent in the environment where it is
--  tested is not an invariant. This fires for everyone.
--
--  A CORRECTION IS A NEW REVERSAL TRANSACTION (SDD 15.6 invariant 8), never an
--  edit. Nothing in this migration creates one, because nothing needs one until
--  Refund -- but this trigger is what makes the reversal the only available
--  option when it arrives, rather than the disciplined option.
-- ----------------------------------------------------------------------------
CREATE FUNCTION ledger_entries_are_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'Ledger entries are immutable; correct a posting with a reversal transaction'
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION ledger_entries_are_immutable();

-- The header is immutable for the same reason: re-pointing a posted journal at
-- a different reference, or editing its currency, rewrites financial history
-- just as effectively as editing a line. Deletion is likewise refused -- the
-- entries' foreign key would already block it, but the error a caller sees
-- should say why rather than name a constraint.
CREATE FUNCTION ledger_transactions_are_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'Ledger transactions are immutable; correct a posting with a reversal transaction'
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_ledger_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW
    EXECUTE FUNCTION ledger_transactions_are_immutable();
