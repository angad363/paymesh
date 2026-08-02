-- ============================================================================
--  V18__create_api_credentials.sql
--  Merchant API credentials. SDD section 9.3 and 9.4, and ADR-022.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entity, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  WHY THIS EXISTS. SDD 10.3 and 11.3 both say customers and orders are created
--  with a "Merchant API key". No such thing existed, so a merchant's backend had
--  to authenticate as a HUMAN WITH A PASSWORD -- the one credential a server
--  must never hold, and one that also carries that person's ability to log in,
--  rotate their own session and act anywhere else they have a role.
--
--  Server-to-server integration was therefore impossible as specified, and the
--  workaround was worse than the gap.
-- ============================================================================

CREATE TABLE api_credentials (
    -- "apc_" + UUID (ADR-003).
    api_credential_id VARCHAR(40)              NOT NULL,

    merchant_id       VARCHAR(40)              NOT NULL,

    -- THE PUBLIC HALF, sent in the clear and used to find the row. Unique across
    -- the whole platform rather than per merchant, and that is a security
    -- property rather than a convenience: authentication is what ESTABLISHES the
    -- merchant, so there is no tenant to scope the lookup by yet. A per-merchant
    -- key space would force the lookup to guess a tenant first, and the guess
    -- itself would be a cross-tenant oracle.
    --
    -- "ak_" + 16 url-safe chars. Not a UUID: this is pasted by humans into
    -- config files, and a short alphabet-dense token is materially less
    -- error-prone than 36 characters of hex and hyphens.
    public_prefix     VARCHAR(40)              NOT NULL,

    -- SHA-256 of the secret half, hex.
    --
    -- NOT BCRYPT, AND THE DIFFERENCE IS DELIBERATE. This is verified on EVERY
    -- API request, where a deliberately slow KDF would be a self-inflicted
    -- denial of service. bcrypt's cost exists to make GUESSING a low-entropy
    -- human password expensive; this secret is 32 bytes from SecureRandom and is
    -- not guessable at any hash speed. Identity's password hashing is bcrypt and
    -- must stay bcrypt, because a human chose that input. The difference is the
    -- entropy of what is hashed, not carelessness in one of them.
    --
    -- The plaintext is returned once and never stored -- same rule as
    -- refresh_tokens (V2). A credential a database reader can use is a shared
    -- password with extra steps.
    secret_hash       CHAR(64)                 NOT NULL,

    -- What the key may do, as the role it authenticates as. A key is not more
    -- powerful than a person: the same role vocabulary applies, so there is one
    -- authorization model rather than two.
    role              VARCHAR(32)              NOT NULL,

    -- A human label. "Which of these six keys is the CI one" is the question an
    -- operator asks at exactly the moment they need to revoke one quickly.
    label             VARCHAR(100)             NOT NULL,

    -- REVOCATION IS A TIMESTAMP, NOT A DELETE. A deleted credential cannot
    -- answer "was this key live when that payment was taken", which is the
    -- question an incident actually asks.
    revoked_at        TIMESTAMP WITH TIME ZONE,

    -- Last use, for finding keys nobody has rotated. Deliberately NOT written on
    -- every request -- see ADR-022: a write per authenticated call would make
    -- this the hottest row in the system and would hold a lock on it for the
    -- duration of every payment.
    last_used_at      TIMESTAMP WITH TIME ZONE,

    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_api_credentials PRIMARY KEY (api_credential_id),

    CONSTRAINT uq_api_credentials_prefix UNIQUE (public_prefix),

    CONSTRAINT fk_api_credentials_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    -- ONLY MERCHANT-SCOPED ROLES. A PLATFORM_ADMIN key would let a string in a
    -- config file suspend merchants and approve KYC; no machine needs that, and
    -- a leaked config file should not be able to do it.
    CONSTRAINT ck_api_credentials_role
        CHECK (role IN ('MERCHANT_ADMIN', 'MERCHANT_USER')),

    -- A revoked credential cannot have been revoked before it existed.
    CONSTRAINT ck_api_credentials_revoked_after_created
        CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

-- Listing a merchant's keys, newest first, which is the only read this table has
-- besides the authentication lookup on the unique prefix.
CREATE INDEX ix_api_credentials_merchant
    ON api_credentials (merchant_id, created_at DESC);
