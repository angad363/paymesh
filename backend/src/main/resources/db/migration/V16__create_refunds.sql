-- ============================================================================
--  V16__create_refunds.sql
--  The Refund capability. SDD section 16, and ADR-019.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  THE INVARIANT THIS FILE EXISTS FOR, and it is SDD 16.6's second line:
--
--      sum(refunds not FAILED and not CANCELLED) <= captured amount
--
--  Refunding more than was collected is the one way this capability can lose
--  real money, and it is reachable by ordinary concurrency rather than by
--  anything exotic: two partial refunds for one payment, submitted at the same
--  moment, each read a total that does not include the other, each pass, and
--  both insert. Neither request did anything wrong.
--
--  WHY A DEFERRED TRIGGER RATHER THAN SDD 16.4's refund_reservations TABLE.
--  A reservation row is a second record of a fact refunds.status already
--  carries, and two records of one fact can disagree -- the same argument that
--  kept account_balances out of V15. It would also not fix the race on its own:
--  a UNIQUE reference stops the same reservation twice, not two different
--  reservations that jointly overshoot. What actually settles it is an
--  aggregate check at COMMIT, which is what this trigger is.
--
--  WHAT THIS MIGRATION DELIBERATELY DOES NOT CREATE, all of SDD 16.4:
--    * refund_reservations -- see above.
--    * refund_attempts -- the provider-call history. payment_attempts exists
--      because a payment is confirmed, challenged and captured over several
--      provider round trips; a refund is asked for once and answered once. The
--      callback dedup table below already records what arrived. ADR-019.
-- ============================================================================

-- ----------------------------------------------------------------------------
--  refunds -- the aggregate (SDD 16.4)
-- ----------------------------------------------------------------------------
CREATE TABLE refunds (
    -- "ref_" + UUID (ADR-003), the prefix reserved for this capability from the
    -- start. Always exactly 40 chars.
    refund_id             VARCHAR(40)              NOT NULL,

    merchant_id           VARCHAR(40)              NOT NULL,

    -- What is being refunded. NOT a foreign key to payment_intents, and that is
    -- the same call ledger_transactions.reference_id made in V15 for the same
    -- reason -- except here it is sharper, because the over-refund trigger
    -- below DOES join payment_intents. So the two modules are already coupled
    -- in the schema, and pretending otherwise with a missing FK would be
    -- theatre.
    --
    -- It is left off deliberately anyway: a foreign key is a delete-time and
    -- insert-time constraint enforced in both directions, and Refund is meant
    -- to be extractable. The trigger is a read of another table at commit,
    -- which becomes an API call on the day these are two services. ADR-019.
    payment_intent_id     VARCHAR(40)              NOT NULL,

    -- Minor units, always positive. A refund of zero is not a refund.
    amount_minor          BIGINT                   NOT NULL,
    currency              CHAR(3)                  NOT NULL,

    -- PENDING -> PROCESSING -> SUCCEEDED | FAILED, or PENDING -> CANCELLED.
    -- Constrained here rather than left to the application because a status
    -- this table does not recognise would be counted as "active" by the trigger
    -- below -- or, worse, not counted at all, which is how an over-refund gets
    -- through a guard that is working perfectly.
    status                VARCHAR(20)              NOT NULL,

    -- The merchant's own reference, unique per merchant where present. Their
    -- idempotency handle in their own vocabulary, exactly as orders have one.
    merchant_reference    VARCHAR(100),

    -- Why the merchant says they are refunding. Free text, never interpreted.
    reason                VARCHAR(200),

    -- What the provider called it. NULL until the provider answers.
    provider_reference    VARCHAR(100),

    failure_code          VARCHAR(64),
    failure_message       VARCHAR(500),

    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_refunds PRIMARY KEY (refund_id),

    CONSTRAINT fk_refunds_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    CONSTRAINT ck_refunds_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),

    CONSTRAINT ck_refunds_amount_positive CHECK (amount_minor > 0),

    CONSTRAINT ck_refunds_currency CHECK (currency ~ '^[A-Z]{3}$'),

    -- Scoped by merchant, so two merchants may both use "REFUND-1". Partial,
    -- because the reference is optional and NULLs must not collide.
    CONSTRAINT uq_refunds_merchant_reference UNIQUE (merchant_id, merchant_reference)
);

-- The list endpoint: this merchant's refunds, newest first, with the id as the
-- tiebreak the cursor needs.
CREATE INDEX ix_refunds_merchant
    ON refunds (merchant_id, created_at DESC, refund_id DESC);

-- The over-refund check joins on this, and so does "show me this payment's
-- refunds". Both are hot.
CREATE INDEX ix_refunds_payment_intent
    ON refunds (payment_intent_id, status);

-- ----------------------------------------------------------------------------
--  refund_state_history -- the timeline (SDD 16.4)
-- ----------------------------------------------------------------------------
CREATE TABLE refund_state_history (
    refund_state_history_id BIGSERIAL                NOT NULL,

    merchant_id             VARCHAR(40)              NOT NULL,
    refund_id               VARCHAR(40)              NOT NULL,

    -- NULL on creation: there is no state before the first one.
    from_status             VARCHAR(20),
    to_status               VARCHAR(20)              NOT NULL,

    -- MERCHANT when a person asked, PROVIDER when a callback decided, SYSTEM
    -- when a timer or a consumer did. actor_id is NULL for the last two,
    -- because a timer has nobody to name.
    actor_type              VARCHAR(20)              NOT NULL,
    actor_id                VARCHAR(80),

    reason                  VARCHAR(200),

    occurred_at             TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_refund_state_history PRIMARY KEY (refund_state_history_id),

    CONSTRAINT fk_refund_state_history_refund FOREIGN KEY (refund_id)
        REFERENCES refunds (refund_id),

    CONSTRAINT ck_refund_state_history_actor
        CHECK (actor_type IN ('MERCHANT', 'PROVIDER', 'SYSTEM'))
);

CREATE INDEX ix_refund_state_history_refund
    ON refund_state_history (refund_id, occurred_at);

-- ----------------------------------------------------------------------------
--  refund_callbacks -- the provider's word, deduplicated (mirrors V10)
-- ----------------------------------------------------------------------------
--  Refund's own table rather than a share of payment's provider_callbacks, and
--  that is the point of ADR-019: Refund owns its callback route end to end, so
--  it owns the dedup too. The two tables have the same shape because they solve
--  the same problem, not because either depends on the other.
-- ----------------------------------------------------------------------------
CREATE TABLE refund_callbacks (
    -- The provider's own event identifier, scoped by provider. THE dedup key,
    -- and the reason it is the primary key rather than a unique index: a
    -- redelivered callback must collide on insert, not be detected by a read
    -- that another delivery can race.
    provider          VARCHAR(40)              NOT NULL,
    external_event_id VARCHAR(100)             NOT NULL,

    refund_id         VARCHAR(40)              NOT NULL,

    outcome           VARCHAR(20)              NOT NULL,

    -- SHA-256 of the raw body, hex. Taken in the signature filter, where the
    -- raw bytes still exist. Two deliveries sharing an event id but differing
    -- here is the signature of a provider bug, and an investigator needs to be
    -- able to see it.
    payload_hash      CHAR(64)                 NOT NULL,

    -- The provider's clock. Used to refuse a stale callback that overtakes a
    -- newer one, the same ordering guard ADR-012 applies to payments.
    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL,

    received_at       TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_refund_callbacks PRIMARY KEY (provider, external_event_id),

    CONSTRAINT fk_refund_callbacks_refund FOREIGN KEY (refund_id)
        REFERENCES refunds (refund_id),

    CONSTRAINT ck_refund_callbacks_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED'))
);

CREATE INDEX ix_refund_callbacks_refund
    ON refund_callbacks (refund_id, occurred_at DESC);

-- ----------------------------------------------------------------------------
--  SDD 16.6 invariant 2: refunds never exceed what was captured.
-- ----------------------------------------------------------------------------
--  A DEFERRED CONSTRAINT TRIGGER, for the same reason V15's balance check is
--  one: the rule is about a SET of rows, so it cannot be a CHECK, and it has to
--  be evaluated when the set is final.
--
--  WHAT COUNTS AS SPOKEN FOR. Everything except FAILED and CANCELLED. A PENDING
--  or PROCESSING refund has not returned any money yet, but the provider may be
--  about to -- counting only SUCCEEDED would let a merchant queue ten full
--  refunds of one payment while the first is still in flight, and every one of
--  them would be individually valid. FAILED and CANCELLED are excluded because
--  both are terminal states in which no money moved and none will.
--
--  WHY IT ALSO FIRES ON UPDATE. A refund's amount is fixed at creation, but its
--  STATUS moves, and a status moving out of FAILED back into PROCESSING would
--  re-arm an amount the check had already discounted. Nothing does that today;
--  the trigger does not depend on nothing doing it.
--
--  THE COMPARISON IS AGAINST captured_amount_minor, NEVER amount_minor. An
--  authorized-but-not-captured payment has collected nothing, and refunding
--  against the authorization would send out money that never came in. On a
--  partial capture the two differ by exactly the amount that was never taken.
--
--  A test inserts a second refund that overshoots, with the application
--  entirely out of the path, and this is what refuses it.
-- ----------------------------------------------------------------------------
CREATE FUNCTION refunds_assert_within_captured() RETURNS TRIGGER AS $$
DECLARE
    active_total BIGINT;
    captured     BIGINT;
BEGIN
    SELECT COALESCE(SUM(r.amount_minor), 0)
      INTO active_total
      FROM refunds r
     WHERE r.payment_intent_id = NEW.payment_intent_id
       AND r.status NOT IN ('FAILED', 'CANCELLED');

    SELECT p.captured_amount_minor
      INTO captured
      FROM payment_intents p
     WHERE p.payment_intent_id = NEW.payment_intent_id;

    -- No payment intent at all. Not this trigger's rule to enforce -- there is
    -- deliberately no foreign key -- but refusing is the only safe reading:
    -- "unknown captured amount" cannot be treated as "enough".
    IF captured IS NULL THEN
        RAISE EXCEPTION
            'Refund % names payment intent %, which does not exist',
            NEW.refund_id, NEW.payment_intent_id
            USING ERRCODE = 'check_violation';
    END IF;

    IF active_total > captured THEN
        RAISE EXCEPTION
            'Refunds of payment intent % total % which exceeds the % captured',
            NEW.payment_intent_id, active_total, captured
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER tr_refunds_within_captured
    AFTER INSERT OR UPDATE ON refunds
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION refunds_assert_within_captured();

-- ----------------------------------------------------------------------------
--  A refund is denominated in the payment's currency, not the caller's.
-- ----------------------------------------------------------------------------
--  Without this, a 5000 JPY refund against a 5000 INR capture passes the
--  amount check exactly, because the trigger above compares integers and
--  integers carry no currency. The money going out would be roughly sixty times
--  the money that came in, and every constraint in this file would be satisfied.
--
--  Separate from the amount trigger rather than folded into it: this one is a
--  per-row fact about a single refund, it can be checked immediately, and the
--  earlier a wrong currency fails the clearer the error is.
-- ----------------------------------------------------------------------------
CREATE FUNCTION refunds_assert_currency_matches() RETURNS TRIGGER AS $$
DECLARE
    payment_currency CHAR(3);
BEGIN
    SELECT p.currency
      INTO payment_currency
      FROM payment_intents p
     WHERE p.payment_intent_id = NEW.payment_intent_id;

    IF payment_currency IS NOT NULL AND payment_currency <> NEW.currency THEN
        RAISE EXCEPTION
            'Refund % is in % but payment intent % is in %',
            NEW.refund_id, NEW.currency, NEW.payment_intent_id, payment_currency
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_refunds_currency_matches
    BEFORE INSERT OR UPDATE ON refunds
    FOR EACH ROW
    EXECUTE FUNCTION refunds_assert_currency_matches();
