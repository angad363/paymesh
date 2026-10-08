-- ============================================================================
--  V1__adopt_risk_schema.sql
--  Risk's own tables. ADR-030 (the capability), ADR-038 (schema-per-service),
--  ADR-039 (the merchant_ref projection, NOT carried into this deployable --
--  see below), ADR-044 (the extraction).
--
--  THIS MODULE'S OWN FLYWAY HISTORY, SEPARATE FROM THE MONOLITH'S (ADR-044,
--  following the ADR-041/042/043 recipe). These tables already exist in the
--  shared cluster's `risk` schema: risk_assessments/denylist_entries are the
--  monolith's V27/V28, moved into this schema by V38. This file is byte-for-byte
--  that live shape, so a FRESH database (a new environment, a Testcontainers
--  run) gets the same schema either way. Against the EXISTING shared dev
--  database, `baseline-on-migrate` (application.yaml) makes Flyway ADOPT the
--  already-present tables at this version instead of re-running CREATE TABLE
--  against a schema that already has them.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  WHAT IS DIFFERENT FROM THE MONOLITH'S ORIGINAL V27/V28, AND WHY IT IS STILL
--  "byte-for-byte the live shape": the monolith's `fk_risk_assessments_merchant`
--  and `fk_denylist_entries_merchant` constraints were already DROPPED by the
--  monolith's own V39 in favour of the merchant_ref projection, before this
--  module ever existed. The live `risk` schema this migration adopts has never
--  had those two constraints since V39 shipped, so omitting them here is
--  adopting reality, not diverging from it -- and it could not be otherwise:
--  `risk_svc` has no `merchants` table in its reach to reference (ADR-038
--  fencing). The identifier-format CHECK on each merchant_id column (V29) is
--  kept; only the cross-schema FK is gone.
--
--  merchant_ref IS NOT ADOPTED HERE, unlike webhook's and engagement's V1
--  (ADR-044 section 1). V39 created a copy in every schema that carried a
--  `* -> merchants` FK, `risk` among them -- but nothing in this capability
--  ever read it: Risk's confirm-time decision is Payment's to gate, not
--  Risk's (ADR-030, "Risk decides, Payment acts"), so no MerchantStatusGate
--  was ever wired here even in the monolith. This deployable therefore never
--  maps that table and never needs it adopted; the live row stays monolith
--  -written collateral until the monolith's own copy of MerchantRefStore drops
--  `risk` from its schema list at a later PR, the same way it already dropped
--  `webhook` and `notification`/`reporting`/`audit` at their own extractions.
-- ============================================================================


-- --- the shared identifier-format predicate (V29 in the monolith) ----------
-- ADR-003's <prefix>_<uuid> shape, enforced in the database rather than only
-- in each XxxId value object's compact constructor. Defined in `public` (not
-- `risk`) because it is a platform-wide predicate several schemas rely on,
-- exactly where the monolith's own migration put it. CREATE OR REPLACE, not
-- CREATE: on a database that already has it (any environment sharing a
-- cluster with the monolith) this is a same-definition no-op; on a genuinely
-- fresh one (Testcontainers) it is the only place this module defines it --
-- the same call provider-sim's, webhook's and engagement's own V1 each make.
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


-- ----------------------------------------------------------------------------
--  risk_assessments -- the record of what Risk decided, and why (SDD 14, ADR-030)
-- ----------------------------------------------------------------------------
CREATE TABLE risk_assessments (
    assessment_id     VARCHAR(40)              NOT NULL,
    merchant_id       VARCHAR(40)              NOT NULL,
    payment_intent_id VARCHAR(40)              NOT NULL,
    outcome           VARCHAR(16)              NOT NULL,
    matched_rules     JSONB                    NOT NULL,
    ruleset_version   INTEGER                  NOT NULL,
    features          JSONB                    NOT NULL,
    decided_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_risk_assessments PRIMARY KEY (assessment_id),

    CONSTRAINT uq_risk_assessments_merchant_assessment
        UNIQUE (merchant_id, assessment_id),

    CONSTRAINT ck_risk_assessments_outcome CHECK (
        outcome IN ('ALLOW', 'REVIEW', 'BLOCK')
    ),

    CONSTRAINT ck_risk_assessments_ruleset_version CHECK (ruleset_version >= 1),

    CONSTRAINT ck_risk_assessments_matched_rules_shape CHECK (
        jsonb_typeof(matched_rules) = 'array'
    ),
    CONSTRAINT ck_risk_assessments_features_shape CHECK (
        jsonb_typeof(features) = 'object'
    ),

    CONSTRAINT ck_risk_assessments_id_format
        CHECK (is_prefixed_id(assessment_id, 'rsk_')),
    CONSTRAINT ck_risk_assessments_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_risk_assessments_intent_id_format
        CHECK (is_prefixed_id(payment_intent_id, 'pi_'))
);

CREATE OR REPLACE FUNCTION refuse_risk_assessment_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'risk_assessments is append-only: % on assessment_id % was refused',
        TG_OP, COALESCE(OLD.assessment_id, NEW.assessment_id)
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_risk_assessments_immutable
    BEFORE UPDATE OR DELETE ON risk_assessments
    FOR EACH ROW
EXECUTE FUNCTION refuse_risk_assessment_mutation();

CREATE INDEX idx_risk_assessments_merchant_recent
    ON risk_assessments (merchant_id, decided_at DESC);

CREATE INDEX idx_risk_assessments_intent
    ON risk_assessments (merchant_id, payment_intent_id);


-- ----------------------------------------------------------------------------
--  denylist_entries -- the things a merchant refuses to take money from
--  (SDD 14, ADR-030)
-- ----------------------------------------------------------------------------
CREATE TABLE denylist_entries (
    entry_id      VARCHAR(40)              NOT NULL,
    merchant_id   VARCHAR(40)              NOT NULL,
    entity_type   VARCHAR(20)              NOT NULL,
    hashed_value  VARCHAR(64)              NOT NULL,
    reason        VARCHAR(500),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_denylist_entries PRIMARY KEY (entry_id),

    CONSTRAINT uq_denylist_entries_merchant_entry UNIQUE (merchant_id, entry_id),

    CONSTRAINT ck_denylist_entries_type CHECK (
        entity_type IN ('CUSTOMER', 'DEVICE')
    ),

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

CREATE UNIQUE INDEX uq_denylist_entries_value
    ON denylist_entries (merchant_id, entity_type, hashed_value);

CREATE INDEX idx_denylist_entries_lookup
    ON denylist_entries (merchant_id, hashed_value, expires_at);
