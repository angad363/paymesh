-- ============================================================================
--  V11__create_order_state_history.sql
--  Creates order_state_history (SDD section 11.4) and the one index the expiry
--  sweeper reads.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  WHY NOW AND NOT IN V5. V5 said it plainly: with exactly one reachable
--  transition, cancellation_reason + cancelled_at on the row carried the whole
--  history, "and the table arrives when a third transition does". It has. This
--  migration ships alongside EXPIRED becoming reachable, which makes three
--  (create, cancel, expire), and Payment will drive more the moment an
--  order.status consumer exists.
--
--  THERE IS NO BACKFILL AND THERE WILL NOT BE ONE. Every order that already
--  exists has no timeline here and never will: the only facts recoverable for
--  it are created_at and, if it was cancelled, cancelled_at + the reason -- and
--  synthesising rows from those would be inventing an audit trail rather than
--  recording one. from_status, actor_id and occurred_at for a pre-migration
--  cancellation are unknowable. A history table whose oldest rows are guesses is
--  worse than one that starts late and says so, because only the second kind can
--  be trusted. THE TIMELINE STARTS AT THIS MIGRATION; anything before it is
--  outside the table's coverage, by construction.
--
--  This is the cost payment_state_history avoided by shipping in V8, before any
--  intent existed. Order did not have that option.
-- ============================================================================

CREATE TABLE order_state_history (
    -- A plain sequence, not a prefixed identifier. ADR-003 governs identifiers
    -- that appear in an API and this one never does -- the same reasoning that
    -- left payment_state_history and idempotency_records without one.
    order_state_history_id BIGINT                   GENERATED ALWAYS AS IDENTITY,

    -- Owning tenant, carried on the history row rather than only reached through
    -- the order, so that every read of this table is merchant-scoped without a
    -- join. It is also half of the composite foreign key below.
    merchant_id            VARCHAR(40)              NOT NULL,

    order_id               VARCHAR(40)              NOT NULL,

    -- NULL for the creation row: an order that has just been created came from
    -- nowhere, and writing PENDING into both columns would claim a transition
    -- that never happened. Mirrors payment_state_history.from_status.
    from_status            VARCHAR(32),

    to_status              VARCHAR(32)              NOT NULL,

    -- Who caused it. MERCHANT is an API call (create, cancel); SYSTEM is the
    -- expiry sweeper, which has no principal to name.
    --
    -- PROVIDER IS DELIBERATELY NOT IN THIS SET, and that is the one place this
    -- table does not mirror payment_state_history. A provider callback never
    -- touches an order: Payment does not write the orders table at all (design
    -- spec 0.5), and when order.status finally moves on a payment it will be
    -- SYSTEM -- an Order-owned consumer of payment.succeeded acting on Order's
    -- own table -- not the provider reaching across the boundary. Admitting
    -- PROVIDER here would make that boundary violation spellable.
    actor_type             VARCHAR(20)              NOT NULL,

    -- Which one, when it is knowable and useful: the merchant id for an API
    -- call. Nullable because SYSTEM actions have no principal.
    actor_id               VARCHAR(80),

    -- The cancellation reason, or why the system moved it. Same width as
    -- orders.cancellation_reason so a reason that fits the row fits its history.
    reason                 VARCHAR(200),

    occurred_at            TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_order_state_history PRIMARY KEY (order_state_history_id),

    -- COMPOSITE, for the tenant-safety reason every foreign key in this schema
    -- is composite: a reference to order_id alone would let one merchant's
    -- history row point at another merchant's order. The referenced pair is
    -- unique thanks to uq_orders_merchant_order, added by V8 for exactly this
    -- class of key.
    CONSTRAINT fk_order_state_history_order
        FOREIGN KEY (merchant_id, order_id)
        REFERENCES orders (merchant_id, order_id),

    CONSTRAINT ck_order_state_history_actor
        CHECK (actor_type IN ('MERCHANT', 'SYSTEM'))
);

-- APPEND-ONLY IS ENFORCED IN CODE, NOT HERE, and that is worth naming. There is
-- no UPDATE or DELETE path anywhere in the application -- no setter on the JPA
-- entity, one method on the repository port -- but the table itself would accept
-- either from a psql prompt. Making it genuinely immutable needs a rule or a
-- trigger, and this project has neither yet for payment_state_history. The two
-- tables stay consistent with each other rather than one of them being quietly
-- stricter.

-- One order's timeline, oldest first. The only access pattern this table has,
-- and merchant-leading like every other index in the capability.
CREATE INDEX idx_order_state_history_order
    ON order_state_history (merchant_id, order_id, occurred_at);


-- ============================================================================
--  The expiry sweeper's access path.
-- ============================================================================
--
-- THE ONE INDEX ON orders THAT DOES NOT LEAD WITH merchant_id, AND THE
-- EXCEPTION IS THE POINT. V5's rule -- "every one is composite and leads with
-- merchant_id, because every query in this capability is tenant-scoped" -- was
-- true of every query that existed then. The sweeper is the first that is not:
-- it runs on a timer with no caller, no token and therefore no tenant, and it
-- must find expirable orders ACROSS all merchants in one pass. A
-- merchant-leading index cannot serve that query; it would force a scan.
--
-- Being tenant-agnostic is not the same as being tenant-unsafe. The sweeper
-- reads the merchant off each candidate row and scopes every subsequent write --
-- the lock, the history row, the event -- by it. See ExpireOrdersService.
--
-- Partial on both predicates: an order that is not PENDING can never become
-- EXPIRED, and an order with no expiry never expires at all. In a healthy system
-- almost every row is one or the other, so the index stays a small fraction of
-- the table.
CREATE INDEX idx_orders_expirable
    ON orders (expires_at)
    WHERE status = 'PENDING' AND expires_at IS NOT NULL;
