-- ============================================================================
--  V7__create_outbox_events.sql
--  Creates outbox_events: the durable record of every domain event a capability
--  has decided to publish. SDD section 22.3, ADR-010.
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entity, because Hibernate runs ddl-auto=validate and fails fast on drift.
--
--  THE INVARIANT THIS TABLE EXISTS TO PROTECT: a committed state change and the
--  event announcing it are the same fact, so they must commit or fail together.
--  A row here is written in the SAME database transaction as the state change it
--  describes -- that is the entire reason the outbox is a table in this database
--  rather than a message handed to a broker. A broker write cannot join a
--  PostgreSQL transaction, so "save the order, then publish" has a window in
--  which the order exists and the event does not, forever.
--
--  THERE IS NO RELAY YET, AND THAT IS A NAMED SAFE STATE. Nothing reads this
--  table, so every row stays unpublished. SDD section 24 lists exactly this
--  outcome as non-corrupting: "DB commits; Kafka unavailable -> outbox remains
--  unpublished -> relay retries." Building a publisher against a broker that
--  does not exist would be scaffolding; the rows written now are the ones the
--  relay will read when it lands.
--
--  DELIVERY IS AT-LEAST-ONCE, NEVER EXACTLY-ONCE. event_id below is the value a
--  consumer dedups on (SDD section 22.4's inbox), which is why it is a public,
--  prefixed identifier rather than a sequence number.
-- ============================================================================

CREATE TABLE outbox_events (
    -- Opaque, application-generated identifier "evt_" + UUID (ADR-003). Always
    -- exactly 40 chars (4 prefix + 36 UUID), so VARCHAR(40) fits it exactly.
    -- Unlike idempotency_records, this identifier IS public: it travels in the
    -- event envelope (SDD 22.1) and a consumer stores it to make a duplicate
    -- delivery a no-op. Minted per event, never derived from the clock, so two
    -- events created in the same instant are still distinguishable.
    event_id        VARCHAR(40)              NOT NULL,

    -- Owning tenant, copied from the aggregate the event is about and never from
    -- a caller. Same "mrc_" + UUID shape as merchants.merchant_id. It is here so
    -- a relay can partition and a consumer can scope without parsing the payload.
    merchant_id     VARCHAR(40)              NOT NULL,

    -- What kind of thing the event is about: 'ORDER', later 'PAYMENT_INTENT'.
    -- Deliberately NOT constrained by a CHECK: this is shared platform code, and
    -- a CHECK listing every capability's aggregates would need a migration from
    -- whichever capability lands next -- the exact coupling a shared table has to
    -- avoid. The producing capability owns the vocabulary.
    aggregate_type  VARCHAR(40)              NOT NULL,

    -- Which one. There is deliberately NO foreign key here: the target table
    -- depends on aggregate_type, and polymorphic foreign keys do not exist. An
    -- FK per aggregate type would also mean deleting an order could not leave its
    -- history behind, which is the opposite of what an event log is for.
    aggregate_id    VARCHAR(40)              NOT NULL,

    -- The event name as consumers subscribe to it: 'order.created',
    -- 'payment.succeeded'. Dotted lower case, matching SDD 22.1.
    event_type      VARCHAR(80)              NOT NULL,

    -- Envelope version (SDD 22.1). Carried per event rather than per topic so a
    -- producer can evolve one event's shape without renaming it, and a consumer
    -- can refuse a version it does not understand instead of guessing.
    event_version   INTEGER                  NOT NULL,

    -- The event body. JSONB (not JSON, not TEXT): stored parsed, so a malformed
    -- payload is rejected on write rather than discovered by a consumer at 3am.
    payload         JSONB                    NOT NULL,

    -- When the fact happened, per the application's Clock -- not when the row was
    -- inserted and not when it is published. TIMESTAMP WITH TIME ZONE so the
    -- value is an unambiguous absolute point in time (stored as UTC), matching
    -- java.time.Instant. Supplied by the app, so no DB DEFAULT: the application
    -- stays the source of time, which is also what lets a test create two events
    -- at exactly the same instant on purpose.
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,

    -- When a relay published it. NULL MEANS UNPUBLISHED, AND THAT IS THE WHOLE
    -- STATUS MODEL. There is no status column, because a status column here
    -- would carry exactly one bit of information that this timestamp already
    -- carries, plus the standing risk of the two disagreeing (ADR-009 refused a
    -- status that exists only to be ignored, for the same reason).
    --
    -- Nothing writes this column today. There is no relay, so it is NULL on every
    -- row, and no Java code maps it.
    published_at    TIMESTAMP WITH TIME ZONE,

    -- The identity is the event. Consumers dedup on this value, so it has to be
    -- unique platform-wide, not per merchant -- an event id that repeated across
    -- tenants would be processed once per tenant by any consumer that is not
    -- itself tenant-scoped.
    CONSTRAINT pk_outbox_events PRIMARY KEY (event_id),

    -- SINGLE-COLUMN ON PURPOSE, and this is the exception to the composite-FK
    -- rule rather than a violation of it. The composite form exists so that a
    -- foreign key CARRYING a tenant alongside another tenant-scoped id cannot
    -- point across tenants (fk_orders_customer). Here merchant_id IS the tenant
    -- column and there is nothing to pair it with. RESTRICT (the default) rather
    -- than CASCADE: deleting a merchant that still has unpublished events must
    -- fail loudly, not silently erase announcements nobody has received.
    CONSTRAINT fk_outbox_events_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants (merchant_id),

    -- An unversioned envelope cannot be evolved, so version zero is corruption
    -- rather than a default. Defense in depth: the domain refuses it too.
    CONSTRAINT ck_outbox_events_version CHECK (event_version > 0)
);

-- THE RELAY'S CLAIM QUERY, and the only index this table gets. A relay polls
-- "oldest unpublished first"; PARTIAL on published_at IS NULL so the index holds
-- only the backlog and shrinks back to nothing once a relay keeps up, instead of
-- growing forever alongside a table that is append-only.
--
-- No index on merchant_id, aggregate_id or event_type. Nothing queries by them
-- yet, and an index no query uses is a write cost on the path that every money
-- movement now goes through.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (occurred_at)
    WHERE published_at IS NULL;
