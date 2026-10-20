-- ============================================================================
--  V1__adopt_merchant_schema.sql
--  The merchant service's own tables: merchants, merchant_status_history,
--  kyc_submissions, api_credentials (ADR-038 schema-per-service, ADR-046).
--
--  THIS MODULE'S OWN FLYWAY HISTORY (ADR-046, ADR-041..045 recipe). These four
--  tables already exist in the shared cluster's `merchant` schema: the
--  monolith's V1, V17, V18 (create) and V26 (identifier-format CHECKs), moved
--  into this schema by V38. This file is that live shape (reconstructed from a
--  pg_dump of the monolith's V1..V40 applied to a clean database), so a FRESH
--  database gets the same schema. Against the EXISTING shared dev database,
--  `baseline-on-migrate` makes Flyway ADOPT the present tables at this version.
--
--  NO FOREIGN KEY TO DROP: every FK here (api_credentials, kyc_submissions,
--  merchant_status_history -> merchants) stays inside the `merchant` schema.
--  All identifier-format CHECKs (V26) are kept.
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


CREATE TABLE merchants (
    merchant_id       VARCHAR(40)              NOT NULL,
    business_name     VARCHAR(200)             NOT NULL,
    email             VARCHAR(320)             NOT NULL,
    country           CHAR(2)                  NOT NULL,
    default_currency  CHAR(3)                  NOT NULL,
    status            VARCHAR(32)              NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_merchants PRIMARY KEY (merchant_id),
    CONSTRAINT uq_merchants_email UNIQUE (email),
    CONSTRAINT ck_merchants_status CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'CLOSED')
    ),
    CONSTRAINT ck_merchants_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);


CREATE TABLE merchant_status_history (
    merchant_status_history_id BIGSERIAL               NOT NULL,
    merchant_id                VARCHAR(40)              NOT NULL,
    from_status                VARCHAR(32),
    to_status                  VARCHAR(32)              NOT NULL,
    actor_type                 VARCHAR(20)              NOT NULL,
    actor_id                   VARCHAR(80),
    reason                     VARCHAR(200),
    occurred_at                TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_merchant_status_history PRIMARY KEY (merchant_status_history_id),
    CONSTRAINT fk_merchant_status_history_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),
    CONSTRAINT ck_merchant_status_history_actor CHECK (actor_type IN ('PLATFORM', 'SYSTEM')),
    CONSTRAINT ck_merchant_status_history_actor_id CHECK (
        (actor_type = 'PLATFORM' AND actor_id IS NOT NULL)
        OR (actor_type = 'SYSTEM' AND actor_id IS NULL)
    ),
    CONSTRAINT ck_merchant_status_history_status CHECK (
        to_status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'CLOSED')
    ),
    CONSTRAINT ck_merchant_status_history_reason CHECK (
        to_status NOT IN ('SUSPENDED', 'CLOSED') OR reason IS NOT NULL
    ),
    CONSTRAINT ck_merchant_status_history_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);

CREATE INDEX ix_merchant_status_history_merchant
    ON merchant_status_history (merchant_id, occurred_at DESC);


CREATE TABLE kyc_submissions (
    kyc_submission_id VARCHAR(40)              NOT NULL,
    merchant_id       VARCHAR(40)              NOT NULL,
    status            VARCHAR(20)              NOT NULL,
    legal_name        VARCHAR(200)             NOT NULL,
    registration_id   VARCHAR(100)             NOT NULL,
    reviewed_by       VARCHAR(80),
    review_notes      VARCHAR(500),
    reviewed_at       TIMESTAMP WITH TIME ZONE,
    submitted_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_kyc_submissions PRIMARY KEY (kyc_submission_id),
    CONSTRAINT fk_kyc_submissions_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),
    CONSTRAINT ck_kyc_submissions_status CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_kyc_submissions_review CHECK (
        (status = 'SUBMITTED' AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
    ),
    CONSTRAINT ck_kyc_submissions_id_format CHECK (is_prefixed_id(kyc_submission_id, 'kyc_')),
    CONSTRAINT ck_kyc_submissions_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);

CREATE UNIQUE INDEX uq_kyc_submissions_open
    ON kyc_submissions (merchant_id) WHERE status = 'SUBMITTED';

CREATE INDEX ix_kyc_submissions_merchant
    ON kyc_submissions (merchant_id, submitted_at DESC);


CREATE TABLE api_credentials (
    api_credential_id VARCHAR(40)              NOT NULL,
    merchant_id       VARCHAR(40)              NOT NULL,
    public_prefix     VARCHAR(40)              NOT NULL,
    secret_hash       CHAR(64)                 NOT NULL,
    role              VARCHAR(32)              NOT NULL,
    label             VARCHAR(100)             NOT NULL,
    revoked_at        TIMESTAMP WITH TIME ZONE,
    last_used_at      TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_api_credentials PRIMARY KEY (api_credential_id),
    CONSTRAINT uq_api_credentials_prefix UNIQUE (public_prefix),
    CONSTRAINT fk_api_credentials_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),
    CONSTRAINT ck_api_credentials_role CHECK (role IN ('MERCHANT_ADMIN', 'MERCHANT_USER')),
    CONSTRAINT ck_api_credentials_revoked_after_created
        CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT ck_api_credentials_id_format CHECK (is_prefixed_id(api_credential_id, 'apc_')),
    CONSTRAINT ck_api_credentials_merchant_id_format CHECK (is_prefixed_id(merchant_id, 'mrc_'))
);

CREATE INDEX ix_api_credentials_merchant
    ON api_credentials (merchant_id, created_at DESC);
