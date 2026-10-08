-- ============================================================================
--  V3__payment_intent_ref.sql
--  Risk's event-fed velocity read model (ADR-044 section 4).
--
--  A NEW TABLE, never present in the monolith's `risk` schema -- this CREATEs
--  on both a fresh database and the adopted shared dev database; there is
--  nothing to baseline past. Fed by PaymentCreatedProjector consuming
--  payment.created (this deployable's own Kafka consumer group, paymesh-risk),
--  read by PaymentIntentRefStore answering the velocity feature Payment used
--  to get by calling GetPaymentIntentService in-process.
--
--  ONE ROW PER INTENT, EVER -- guest checkouts are never inserted (the
--  projector skips them: there is no customer to count velocity against, see
--  EvaluateRiskService.intentsInWindow), and the insert is
--  ON CONFLICT (payment_intent_id) DO NOTHING so a redelivered event is a
--  no-op rather than a constraint violation.
-- ============================================================================

CREATE TABLE payment_intent_ref (
    payment_intent_id VARCHAR(40)              NOT NULL,
    merchant_id       VARCHAR(40)              NOT NULL,
    customer_id       VARCHAR(40)              NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_payment_intent_ref PRIMARY KEY (payment_intent_id),

    CONSTRAINT ck_payment_intent_ref_intent_id_format
        CHECK (is_prefixed_id(payment_intent_id, 'pi_')),
    CONSTRAINT ck_payment_intent_ref_merchant_id_format
        CHECK (is_prefixed_id(merchant_id, 'mrc_')),
    CONSTRAINT ck_payment_intent_ref_customer_id_format
        CHECK (is_prefixed_id(customer_id, 'cus_'))
);

-- THE VELOCITY QUERY: count this customer's intents since a window start,
-- excluding the one being judged. Leads with (merchant_id, customer_id) --
-- the query's equality predicate -- then created_at for the range scan.
CREATE INDEX idx_payment_intent_ref_merchant_customer_created
    ON payment_intent_ref (merchant_id, customer_id, created_at);
