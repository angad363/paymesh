-- ============================================================================
--  V3__api_credential_ref.sql
--  The credential read model (ADR-048 section 4, option 3): what ApiKey verification needs, fed by
--  merchant.api_credential.issued / .revoked over this service's own consumer group. The same
--  pattern as merchant_ref (ADR-039). NO FK to the merchant service's api_credentials (a sibling
--  schema, ADR-038 fencing).
--
--  secret_hash is a SECOND COPY of a sensitive value (SHA-256 of a 32-byte random secret, so not
--  guessable, but still not public). That, and revocation lagging by the projection delay, is the
--  stated tradeoff of option 3.
-- ============================================================================

CREATE TABLE api_credential_ref (
    api_credential_id VARCHAR(40)              NOT NULL,
    merchant_id       VARCHAR(40)              NOT NULL,
    -- Globally unique, like the source table: authentication establishes the merchant, so the
    -- lookup cannot be scoped by one.
    public_prefix     VARCHAR(40)              NOT NULL,
    secret_hash       CHAR(64)                 NOT NULL,
    role              VARCHAR(32)              NOT NULL,
    revoked_at        TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_api_credential_ref PRIMARY KEY (api_credential_id),
    CONSTRAINT uq_api_credential_ref_prefix UNIQUE (public_prefix),

    CONSTRAINT ck_api_credential_ref_id_format
        CHECK (is_prefixed_id(api_credential_id, 'apc_')),
    CONSTRAINT ck_api_credential_ref_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_api_credential_ref_hash CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    -- Only merchant-scoped roles, as the source table (a PLATFORM_ADMIN key is never issued).
    CONSTRAINT ck_api_credential_ref_role CHECK (role IN ('MERCHANT_ADMIN', 'MERCHANT_USER'))
);
