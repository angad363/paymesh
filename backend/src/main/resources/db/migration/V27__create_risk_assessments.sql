-- =============================================================================
-- V27: the record of what Risk decided, and why. SDD 14, ADR-030.
--
-- One table, not the four the phase-2 plan listed. `risk_rules` is not here
-- because the rules are code (see RiskRuleset: a stored expression language
-- buys changing a rule without a deploy, which nobody has asked for, and costs
-- an evaluator plus a runtime failure mode on the money path). `risk_reviews`
-- is not here because nothing reads a queue yet, and a queue's shape is
-- decided by how it gets worked. `denylist_entries` is V28.
--
-- WHAT THIS TABLE IS FOR
--
-- Answering "why did we block this in March?" after the rules have changed.
-- That is SDD 14.6's reproducibility requirement, and it is why the row stores
-- the ruleset VERSION and the FEATURE SNAPSHOT rather than pointing at rules
-- that can be edited afterwards. A decision that can be re-derived from a live
-- rule table is not evidence of what actually happened.
-- =============================================================================

CREATE TABLE risk_assessments (
    -- rsk_<uuid>, ADR-003, format-checked at the bottom of this file like every
    -- other identifier since V26.
    assessment_id     VARCHAR(40)              NOT NULL,

    merchant_id       VARCHAR(40)              NOT NULL,

    -- NOT a foreign key to payment_intents, and this is deliberate rather than
    -- an oversight.
    --
    -- Risk must be extractable (SDD 30.1 lists it among the first services to
    -- leave the monolith), and a foreign key into Payment's table is a join
    -- that has to be dropped on the day it moves. The Ledger already made this
    -- exact call for `reference_id` in V15 and said so there. It is stored the
    -- way a reference across a service boundary is stored: by value.
    payment_intent_id VARCHAR(40)              NOT NULL,

    -- ALLOW / REVIEW / BLOCK. REQUIRE_ACTION from SDD 14 is deliberately absent
    -- -- PayMesh has no step-up mechanism for Risk to ask for, and an
    -- unreachable enum value is a promise the code cannot keep. See RiskOutcome.
    outcome           VARCHAR(16)              NOT NULL,

    -- The rules that fired, as a JSON array of names, in evaluation order.
    -- Empty array on a clean allow, never NULL: "no rules matched" and "we did
    -- not record which rules matched" are different facts and must not share a
    -- spelling.
    matched_rules     JSONB                    NOT NULL,

    -- RiskRuleset.VERSION at the instant of the decision. The reason this row
    -- stays readable after the rules change.
    ruleset_version   INTEGER                  NOT NULL,

    -- Every input the rules were allowed to see. With ruleset_version, these
    -- reproduce the outcome exactly.
    features          JSONB                    NOT NULL,

    decided_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_risk_assessments PRIMARY KEY (assessment_id),

    -- The tenant pair every other table on the platform carries, so a lookup by
    -- id can be scoped by merchant in one index rather than fetched and then
    -- checked.
    CONSTRAINT uq_risk_assessments_merchant_assessment
        UNIQUE (merchant_id, assessment_id),

    CONSTRAINT fk_risk_assessments_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    CONSTRAINT ck_risk_assessments_outcome CHECK (
        outcome IN ('ALLOW', 'REVIEW', 'BLOCK')
    ),

    CONSTRAINT ck_risk_assessments_ruleset_version CHECK (ruleset_version >= 1),

    -- Both JSONB columns hold a specific SHAPE, and the application reads them
    -- back into a List and a record respectively. V26's own header points at
    -- exactly this hazard: `orders.metadata` is unconstrained JSONB mapped to a
    -- Map, so a JSON array there is a row no mapper can rehydrate. These two
    -- columns are new, so they get the constraint the old ones cannot have
    -- retrofitted without a data migration.
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


-- -----------------------------------------------------------------------------
-- IMMUTABLE, ENFORCED HERE RATHER THAN TRUSTED TO THE REPOSITORY.
--
-- The application port has no update and no delete. This makes that true even
-- for a psql session, a future service that reuses the table, or a repository
-- someone adds a save() to without reading the javadoc. Same instinct and same
-- mechanism as the ledger entries' immutability trigger in V15: an audit record
-- that can be edited is not an audit record.
--
-- DELETE is refused too, not only UPDATE. "We blocked this and then the row
-- vanished" is exactly the shape of the incident this table exists to explain.
-- -----------------------------------------------------------------------------
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


-- -----------------------------------------------------------------------------
-- The one read this table has: a merchant's recent decisions, newest first.
--
-- Partial on nothing and sorted descending because that is the query -- an
-- operator asking "what has Risk been doing?" wants the newest page, and a
-- plain ascending index would be scanned backwards for every one of them.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_risk_assessments_merchant_recent
    ON risk_assessments (merchant_id, decided_at DESC);

-- The support question: "what did Risk say about this specific payment?" There
-- can be more than one row per intent -- a retried confirm is a second
-- evaluation -- so this is not unique.
CREATE INDEX idx_risk_assessments_intent
    ON risk_assessments (merchant_id, payment_intent_id);
