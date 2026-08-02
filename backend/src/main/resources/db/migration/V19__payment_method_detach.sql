-- ============================================================================
--  V19__payment_method_detach.sql
--  Gives payment_method_tokens a writer, at last. SDD section 10.3, ADR-023.
--
--  THE TABLE HAS EXISTED SINCE V3 AND NOTHING HAS EVER WRITTEN A ROW TO IT.
--  SDD 10.3's attach and detach endpoints were never built, so "attach a
--  payment method" on a payment intent attached a payment method TYPE -- the
--  string "CARD" -- and no card was ever on file for any customer. The audit
--  that prompted this change found it as a dangling table: created, indexed,
--  foreign-keyed, and dead.
--
--  V6 even fixed a tenant foreign key on it. A bug was fixed in a table nobody
--  could put a row in.
-- ============================================================================

-- ----------------------------------------------------------------------------
--  Detach is a timestamp, not a DELETE
-- ----------------------------------------------------------------------------
--  Same rule as api_credentials.revoked_at (V18) and refunds' terminal states:
--  a deleted token cannot answer "was this card on file when that payment was
--  taken", which is the question an incident asks. A detached token stays as
--  history and stops being usable.
-- ----------------------------------------------------------------------------
ALTER TABLE payment_method_tokens
    ADD COLUMN detached_at TIMESTAMP WITH TIME ZONE;

-- The live tokens of one customer, which is every read this table has.
CREATE INDEX ix_payment_method_tokens_customer_live
    ON payment_method_tokens (customer_id, created_at DESC)
    WHERE detached_at IS NULL;

-- ----------------------------------------------------------------------------
--  ONE LIVE TOKEN PER FINGERPRINT PER CUSTOMER
-- ----------------------------------------------------------------------------
--  The same card attached twice is two rows the merchant has to reason about
--  and the customer sees as two identical cards -- and picking "the" saved card
--  then depends on insertion order.
--
--  Partial on detached_at, so RE-attaching a card that was detached is allowed.
--  That is a real thing customers do, and a plain unique index would refuse it
--  forever on the strength of a card they removed once.
-- ----------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_payment_method_tokens_live_fingerprint
    ON payment_method_tokens (customer_id, fingerprint)
    WHERE detached_at IS NULL;
