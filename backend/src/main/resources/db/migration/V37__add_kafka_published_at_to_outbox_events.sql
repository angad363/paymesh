-- ============================================================================
--  V37__add_kafka_published_at_to_outbox_events.sql
--  ADR-037: the dual-path relay delivers each event to TWO INDEPENDENT sinks --
--  the in-process dispatcher and Kafka -- and each must complete, and be
--  retried, without the other.
--
--  WHY A SECOND COLUMN, AND NOT ONE published_at FOR BOTH. published_at (V7) is
--  the in-process status model. If it also gated Kafka, then a broker outage --
--  which leaves the row unpublished so it retries -- would keep an event that
--  was ALREADY delivered in-process sitting in the relay's oldest-first claim
--  query. The batch (bounded) then saturates with in-process-done, Kafka-pending
--  rows, and newly committed events never get claimed: a Kafka outage stalls the
--  in-process money path. Two columns decouple the sinks so an in-process-done
--  row LEAVES the in-process claim immediately and the money path never waits on
--  the broker.
-- ============================================================================

ALTER TABLE outbox_events
    -- When the Kafka relay put this event on the broker. NULL means "not on Kafka
    -- yet", exactly as published_at NULL means "not delivered in-process yet".
    -- Unmapped in JPA (like published_at, attempt_count, dead_lettered_at): only
    -- the native relay queries touch it, so ddl-auto=validate ignores it.
    ADD COLUMN kafka_published_at TIMESTAMP WITH TIME ZONE;

-- Already-delivered rows are treated as also Kafka-done, so enabling the dual
-- path does not replay the entire historical backlog onto the broker. A row that
-- was published in-process before this migration never went to Kafka, and there
-- is no consumer that needs it there retroactively (the only Kafka consumer is
-- the monolith's own listener, which dedups through the same inbox). On a fresh
-- database this updates nothing; it matters only where a backlog already exists.
UPDATE outbox_events SET kafka_published_at = published_at WHERE published_at IS NOT NULL;

-- The Kafka relay's claim query, mirroring idx_outbox_events_unpublished for the
-- in-process one (V7, rebuilt in V21): oldest-first over rows not yet on the
-- broker. PARTIAL, so it holds only the Kafka backlog and shrinks to nothing once
-- the relay keeps up. Excludes dead-lettered rows for the same reason the
-- in-process index does -- a row the in-process track has abandoned (an unmappable
-- payload) can never form a Kafka record either, so it must not lead every pass.
CREATE INDEX idx_outbox_events_unpublished_to_kafka ON outbox_events (occurred_at)
    WHERE kafka_published_at IS NULL AND dead_lettered_at IS NULL;
