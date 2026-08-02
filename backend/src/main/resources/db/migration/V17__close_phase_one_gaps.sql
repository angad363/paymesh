-- ============================================================================
--  V17__close_phase_one_gaps.sql
--  The administrative half of Phase 1. ADR-020, ADR-021, ADR-022.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  WHAT THIS FIXES, AND WHY IT IS ONE MIGRATION. The audit that prompted it
--  found the same defect in three places: a lifecycle enum with exactly one
--  reachable value.
--
--      MerchantStatus  PENDING_VERIFICATION | ACTIVE | SUSPENDED | CLOSED
--      UserStatus      ACTIVE | SUSPENDED | CLOSED
--      CustomerStatus  ACTIVE | BLOCKED
--
--  Only the first value of each was ever produced, and nothing anywhere read
--  MerchantStatus at all. The consequences were not cosmetic:
--
--    * No merchant could ever be suspended. A compromised or fraudulent
--      merchant could not be stopped, which is the most important operational
--      control a payment platform has after authentication.
--    * Every merchant transacted while PENDING_VERIFICATION, so verification
--      was decorative.
--    * User.isActive() was checked at login and could never be false.
--
--  The tables below are what make those states reachable AND auditable, plus
--  KYC submissions (SDD 9.3) -- which is not an optional extra here. A status
--  gate whose only entry state is PENDING_VERIFICATION, with no way to leave
--  it, is a lock with no key: it would freeze every newly registered merchant
--  out of the platform permanently. KYC is what makes ACTIVE reachable, so it
--  ships in the same change as the gate or the gate cannot ship at all.
--
--  API credentials (SDD 9.3) and the payment-method endpoints (SDD 10.3) are
--  the other two things the audit found absent. They are deliberately NOT here:
--  each is self-contained, neither is needed to make this change coherent, and
--  a security surface as large as machine authentication deserves its own
--  review rather than a paragraph inside somebody else's.
-- ============================================================================

-- ----------------------------------------------------------------------------
--  merchant_status_history -- who suspended a merchant, when, and why
-- ----------------------------------------------------------------------------
--  A suspension is the single most consequential administrative act on this
--  platform: it stops a business taking money. "Which operator did this and on
--  what grounds" is the first question anyone asks afterwards, and a status
--  column alone cannot answer it.
--
--  Same shape as order_state_history, payment_state_history and
--  refund_state_history, deliberately -- four tables that answer one kind of
--  question should not each invent their own columns.
-- ----------------------------------------------------------------------------
CREATE TABLE merchant_status_history (
    merchant_status_history_id BIGSERIAL                NOT NULL,

    merchant_id                VARCHAR(40)              NOT NULL,

    -- NULL on registration: there is no state before the first one.
    from_status                VARCHAR(32),
    to_status                  VARCHAR(32)              NOT NULL,

    -- PLATFORM for an operator, SYSTEM for anything automatic. NOT "MERCHANT":
    -- a merchant cannot change its own status, which is the whole point of the
    -- control. The CHECK below is what enforces that rather than a comment.
    actor_type                 VARCHAR(20)              NOT NULL,

    -- The operator's user id. Required for PLATFORM, forbidden for SYSTEM --
    -- a timer has nobody to name, and recording one would be a lie in an audit
    -- table.
    actor_id                   VARCHAR(80),

    -- Why. Required on suspend and close, because "we stopped this business
    -- taking money" with no reason recorded is not an audit trail.
    reason                     VARCHAR(200),

    occurred_at                TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_merchant_status_history PRIMARY KEY (merchant_status_history_id),

    CONSTRAINT fk_merchant_status_history_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    CONSTRAINT ck_merchant_status_history_actor
        CHECK (actor_type IN ('PLATFORM', 'SYSTEM')),

    CONSTRAINT ck_merchant_status_history_actor_id CHECK (
        (actor_type = 'PLATFORM' AND actor_id IS NOT NULL)
        OR
        (actor_type = 'SYSTEM'   AND actor_id IS NULL)
    ),

    CONSTRAINT ck_merchant_status_history_status CHECK (
        to_status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'CLOSED')
    ),

    -- A suspension or a closure without a stated reason is refused by the
    -- database, not by a code review.
    CONSTRAINT ck_merchant_status_history_reason CHECK (
        to_status NOT IN ('SUSPENDED', 'CLOSED') OR reason IS NOT NULL
    )
);

CREATE INDEX ix_merchant_status_history_merchant
    ON merchant_status_history (merchant_id, occurred_at DESC);

-- ----------------------------------------------------------------------------
--  customer_status_history -- SDD 10.4's "auditable lifecycle"
-- ----------------------------------------------------------------------------
CREATE TABLE customer_status_history (
    customer_status_history_id BIGSERIAL                NOT NULL,

    merchant_id                VARCHAR(40)              NOT NULL,
    customer_id                VARCHAR(40)              NOT NULL,

    from_status                VARCHAR(20),
    to_status                  VARCHAR(20)              NOT NULL,

    -- MERCHANT here, unlike merchant_status_history: blocking a customer IS
    -- the merchant's own decision about their own buyer, and PayMesh has no
    -- opinion on it. SYSTEM is reserved for a future risk consumer.
    actor_type                 VARCHAR(20)              NOT NULL,
    actor_id                   VARCHAR(80),

    reason                     VARCHAR(200),

    occurred_at                TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_customer_status_history PRIMARY KEY (customer_status_history_id),

    CONSTRAINT fk_customer_status_history_customer FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id),

    CONSTRAINT ck_customer_status_history_actor
        CHECK (actor_type IN ('MERCHANT', 'SYSTEM')),

    CONSTRAINT ck_customer_status_history_actor_id CHECK (
        (actor_type = 'MERCHANT' AND actor_id IS NOT NULL)
        OR
        (actor_type = 'SYSTEM'   AND actor_id IS NULL)
    ),

    CONSTRAINT ck_customer_status_history_status
        CHECK (to_status IN ('ACTIVE', 'BLOCKED'))
);

CREATE INDEX ix_customer_status_history_customer
    ON customer_status_history (customer_id, occurred_at DESC);

-- ----------------------------------------------------------------------------
--  kyc_submissions -- SDD 9.3 and 9.4
-- ----------------------------------------------------------------------------
--  Simulated verification, and the input to the merchant status that did not
--  previously exist. A merchant submits; a platform operator approves or
--  rejects; approval is what moves the merchant to ACTIVE.
--
--  No documents are stored and none are accepted. This is an educational
--  platform that claims no compliance, and a table holding scans of passports
--  would be the single worst thing in the repository.
-- ----------------------------------------------------------------------------
CREATE TABLE kyc_submissions (
    kyc_submission_id VARCHAR(40)              NOT NULL,

    merchant_id       VARCHAR(40)              NOT NULL,

    -- SUBMITTED -> APPROVED | REJECTED. Terminal either way; a rejected
    -- merchant submits again rather than reopening the old one, so every
    -- decision stays on the record.
    status            VARCHAR(20)              NOT NULL,

    -- What the merchant claims. Free text, never interpreted, bounded.
    legal_name        VARCHAR(200)             NOT NULL,
    registration_id   VARCHAR(100)             NOT NULL,

    -- The operator's decision and who made it.
    reviewed_by       VARCHAR(80),
    review_notes      VARCHAR(500),
    reviewed_at       TIMESTAMP WITH TIME ZONE,

    submitted_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_kyc_submissions PRIMARY KEY (kyc_submission_id),

    CONSTRAINT fk_kyc_submissions_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    CONSTRAINT ck_kyc_submissions_status
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED')),

    -- A decided submission names its reviewer and when; an undecided one names
    -- neither. Without this a row can claim to be APPROVED by nobody.
    CONSTRAINT ck_kyc_submissions_review CHECK (
        (status = 'SUBMITTED' AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR
        (status IN ('APPROVED', 'REJECTED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

-- ONE UNDECIDED SUBMISSION PER MERCHANT. Without it a merchant can queue a
-- hundred submissions and an operator has no idea which one is the live
-- request. Partial, so decided submissions accumulate freely as history.
CREATE UNIQUE INDEX uq_kyc_submissions_open
    ON kyc_submissions (merchant_id)
    WHERE status = 'SUBMITTED';

CREATE INDEX ix_kyc_submissions_merchant
    ON kyc_submissions (merchant_id, submitted_at DESC);

-- ----------------------------------------------------------------------------
--  Every merchant that already exists was registered before any of this and is
--  stranded in PENDING_VERIFICATION, which now actually blocks writes.
-- ----------------------------------------------------------------------------
--  Activating them is the only defensible migration. The alternative is that
--  deploying this change silently freezes every existing merchant out of the
--  platform -- turning a security fix into an outage, and punishing merchants
--  for a control that did not exist when they signed up.
--
--  Recorded as a SYSTEM transition with a reason, so the audit trail says
--  exactly why every one of them became ACTIVE at the same instant rather than
--  leaving a gap somebody has to reconstruct later.
-- ----------------------------------------------------------------------------
INSERT INTO merchant_status_history
    (merchant_id, from_status, to_status, actor_type, actor_id, reason, occurred_at)
SELECT merchant_id, status, 'ACTIVE', 'SYSTEM', NULL,
       'Activated by V17: registered before merchant status gated any write', now()
  FROM merchants
 WHERE status <> 'ACTIVE';

UPDATE merchants
   SET status = 'ACTIVE', updated_at = now()
 WHERE status <> 'ACTIVE';
