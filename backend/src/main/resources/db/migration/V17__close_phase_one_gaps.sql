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
--  the two capabilities the audit found entirely absent: API credentials
--  (SDD 9.3 -- without which no merchant backend can integrate without storing
--  a human's password) and KYC submissions (SDD 9.3, the thing that would
--  drive the merchant status that did not exist).
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
--  api_credentials -- SDD 9.3 and 9.4, and the reason server-to-server
--  integration was impossible
-- ----------------------------------------------------------------------------
--  SDD 10.3 and 11.3 say customers and orders are created with a "Merchant API
--  key". No such thing existed, so every integration had to authenticate as a
--  human with a password -- which is exactly the credential a merchant's
--  backend must never hold.
--
--  THE SECRET IS STORED AS A HASH AND RETURNED EXACTLY ONCE. This is the same
--  rule as refresh_tokens (V2) and for the same reason: a credential a database
--  reader can use is not a credential, it is a shared password with extra
--  steps. A merchant who loses the secret creates a new key and revokes the old
--  one; there is no recovery path, deliberately.
-- ----------------------------------------------------------------------------
CREATE TABLE api_credentials (
    api_credential_id VARCHAR(40)              NOT NULL,

    merchant_id       VARCHAR(40)              NOT NULL,

    -- The PUBLIC half, sent in the clear and used to find the row. Unique
    -- across the platform, so a lookup never has to guess a tenant before it
    -- has authenticated one -- which is what stops the lookup itself becoming a
    -- cross-tenant oracle.
    --
    -- "ak_" + 24 url-safe chars. Not a UUID: this is typed and pasted by humans
    -- into config files, and a shorter alphabet-dense token is materially less
    -- error-prone than 36 characters of hex and hyphens.
    public_prefix     VARCHAR(40)              NOT NULL,

    -- SHA-256 of the secret half, hex. NOT bcrypt, and the difference is
    -- deliberate: this is verified on EVERY API request, where a deliberately
    -- slow hash would be a self-inflicted denial of service. The protection
    -- bcrypt buys is against guessing a low-entropy human password; the secret
    -- here is 32 random bytes, which is not guessable at any hash speed.
    secret_hash       CHAR(64)                 NOT NULL,

    -- What the key may do, as the role it authenticates as. A key is not more
    -- powerful than a person: the same role vocabulary applies, so there is one
    -- authorization model rather than two.
    role              VARCHAR(32)              NOT NULL,

    -- A human label, so an operator can tell "CI" from "billing service" when
    -- deciding which to revoke.
    label             VARCHAR(100)             NOT NULL,

    -- Revocation is a timestamp, not a delete. A deleted credential cannot
    -- answer "was this key live when that payment was taken", which is the
    -- question an incident actually asks.
    revoked_at        TIMESTAMP WITH TIME ZONE,

    -- Last use, for finding keys nobody has rotated. Deliberately NOT updated
    -- on every request -- see the adapter: a write on every authenticated call
    -- would make this table the hottest row in the system.
    last_used_at      TIMESTAMP WITH TIME ZONE,

    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_api_credentials PRIMARY KEY (api_credential_id),

    CONSTRAINT uq_api_credentials_prefix UNIQUE (public_prefix),

    CONSTRAINT fk_api_credentials_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    -- Only merchant-scoped roles. A PLATFORM_ADMIN api key would be a way to
    -- suspend merchants with a string in a config file, and there is no reason
    -- for a machine to hold that power.
    CONSTRAINT ck_api_credentials_role
        CHECK (role IN ('MERCHANT_ADMIN', 'MERCHANT_USER'))
);

-- Listing a merchant's keys, and finding the live ones. Partial on revoked_at,
-- because the common query is "which keys still work".
CREATE INDEX ix_api_credentials_merchant
    ON api_credentials (merchant_id, created_at DESC);

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
--  payment_method_tokens gains a detach timestamp
-- ----------------------------------------------------------------------------
--  The table has existed since V3 and NOTHING HAS EVER WRITTEN A ROW TO IT --
--  SDD 10.3's attach/detach endpoints were never built, so "attach a payment
--  method" attached a payment method TYPE and no card was ever on file. The
--  endpoints arrive in this change and the table finally has a writer.
--
--  Detach is a timestamp rather than a DELETE for the same reason revocation is
--  above: a deleted token cannot answer "was this card on file when that
--  payment was taken".
-- ----------------------------------------------------------------------------
ALTER TABLE payment_method_tokens
    ADD COLUMN detached_at TIMESTAMP WITH TIME ZONE;

-- The live tokens of one customer, which is every read this table has.
CREATE INDEX ix_payment_method_tokens_customer_live
    ON payment_method_tokens (customer_id, created_at DESC)
    WHERE detached_at IS NULL;

-- ONE LIVE TOKEN PER FINGERPRINT PER CUSTOMER. The same card attached twice is
-- two rows a merchant has to reason about and a customer sees as two cards.
-- Partial on detached_at, so re-attaching a card that was detached is allowed --
-- which is a real thing customers do.
CREATE UNIQUE INDEX uq_payment_method_tokens_live_fingerprint
    ON payment_method_tokens (customer_id, fingerprint)
    WHERE detached_at IS NULL;

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
