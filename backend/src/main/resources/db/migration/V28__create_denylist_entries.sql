-- =============================================================================
-- V28: the things a merchant refuses to take money from. SDD 14, ADR-030.
--
-- The one part of Risk an operator actually writes to. Rules are code and
-- assessments are a record; this is the live opinion, and it is the reason Risk
-- is useful on the day it ships rather than after someone builds a console.
-- =============================================================================

CREATE TABLE denylist_entries (
    entry_id      VARCHAR(40)              NOT NULL,

    merchant_id   VARCHAR(40)              NOT NULL,

    -- CUSTOMER or DEVICE. Both are matched by EvaluateRiskService against a
    -- feature that exists today; there is no third value because there is no
    -- third thing to match it against.
    entity_type   VARCHAR(20)              NOT NULL,

    -- SHA-256 HEX OF THE VALUE, NEVER THE VALUE.
    --
    -- A denylist accumulates precisely the data nobody wants in a support
    -- export -- the customer who charged back, the device used for fraud. An
    -- equality match is the only operation this table ever supports, so a
    -- digest serves it exactly as well as the plaintext would.
    --
    -- Unsalted, and DenylistHash's javadoc is honest about what that means: the
    -- values are low-entropy, so this does not resist an attacker with a
    -- candidate list. It resists a dump and an operator's idle browsing, which
    -- is the actual threat at this size. Encryption with a managed key is the
    -- answer to the other one, and ADR-006 still owns that question.
    --
    -- 64 chars exactly: SHA-256 hex. CHAR would pad; the length CHECK below
    -- does the same job without the padding.
    hashed_value  VARCHAR(64)              NOT NULL,

    -- For the human who has to explain this to a merchant later. Never
    -- interpreted by any rule.
    reason        VARCHAR(500),

    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,

    -- NULL means "until someone removes it". Most real entries are a response
    -- to a burst rather than a permanent judgement, and a denylist with no
    -- expiry is one that only ever grows.
    expires_at    TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_denylist_entries PRIMARY KEY (entry_id),

    CONSTRAINT uq_denylist_entries_merchant_entry UNIQUE (merchant_id, entry_id),

    CONSTRAINT fk_denylist_entries_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    CONSTRAINT ck_denylist_entries_type CHECK (
        entity_type IN ('CUSTOMER', 'DEVICE')
    ),

    -- Lowercase hex, exactly 64 characters. The same instinct as V26's
    -- identifier CHECKs: the application will only ever write a SHA-256 hex
    -- digest here, so the column should refuse anything that is not one. A
    -- truncated or double-encoded value would simply never match, and a
    -- denylist that silently stops denying is the worst failure this table has.
    CONSTRAINT ck_denylist_entries_hash_format CHECK (
        hashed_value ~ '^[0-9a-f]{64}$'
    ),

    CONSTRAINT ck_denylist_entries_expiry CHECK (
        expires_at IS NULL OR expires_at > created_at
    ),

    CONSTRAINT ck_denylist_entries_id_format
        CHECK (is_prefixed_id(entry_id, 'dnl_')),
    CONSTRAINT ck_denylist_entries_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);


-- -----------------------------------------------------------------------------
-- ONE ENTRY PER (merchant, type, value). Adding the same customer twice is not
-- an error worth surfacing to an operator, but two rows for it means a removal
-- that appears to succeed and leaves the entity still denied -- which reads as
-- "the remove button is broken".
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_denylist_entries_value
    ON denylist_entries (merchant_id, entity_type, hashed_value);


-- -----------------------------------------------------------------------------
-- THE EVALUATION QUERY, and the reason this index exists in this shape.
--
-- Every confirm asks "does any live entry match one of these hashes?" while
-- holding the payment intent's row lock. That query filters on merchant and a
-- small set of hashed values and then discards expired rows, so the index leads
-- with exactly those columns. It is not partial on expiry: a partial index
-- would have to be rebuilt as rows expire, and expiry is a comparison against
-- the caller's clock rather than a fixed predicate PostgreSQL could bake in.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_denylist_entries_lookup
    ON denylist_entries (merchant_id, hashed_value, expires_at);
