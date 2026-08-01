-- ============================================================================
--  V12__index_processing_payment_intents.sql
--  One index. No table, no column, no constraint: the PROCESSING timeout needs
--  an access path and nothing else, because V8 already declared every column it
--  writes (status, failure_code, failure_message, updated_at).
--
--  Schema is authored by hand (Flyway-owned) and MUST match the mapped JPA
--  entities, because Hibernate runs ddl-auto=validate and fails fast on drift.
--  An index is invisible to that check; it is here because the query below runs
--  on a timer over the whole table and a sequential scan every few minutes is
--  not a plan, it is an accident waiting for row count.
-- ============================================================================

-- THE SECOND INDEX IN THIS SCHEMA THAT DOES NOT LEAD WITH merchant_id, for the
-- same reason as idx_orders_expirable in V11: the caller is a scheduled job with
-- no token and therefore no tenant, and it must find stranded intents ACROSS all
-- merchants in one pass. The merchant is read off each candidate row and scopes
-- every write that follows.
--
-- Partial on the only status that can time out. PROCESSING is the one intent
-- state with no local exit (ADR-011): cancel is refused from it by design, so
-- the only way out is a provider callback that may never arrive. Every other
-- status is either terminal or has a merchant-driven route, so none of them
-- belongs in this index -- and in a healthy system PROCESSING is a handful of
-- rows at any instant, which is what makes the partial index nearly free.
--
-- updated_at IS THE AGE CLOCK, and it is the right column rather than a
-- convenient one. It is stamped by the transition that put the intent into
-- PROCESSING -- the confirm, or the re-confirm after a 3DS challenge -- so
-- "PROCESSING since" is exactly what it reads. A re-confirm deliberately RESETS
-- it: the customer completing a challenge starts a fresh wait on the provider,
-- and timing that attempt out against the first confirm's clock would fail a
-- collection that had only just been asked for.
--
-- What it is NOT is payment_attempts.last_provider_event_at. That column is the
-- monotonic ordering guard (ADR-012) and the timeout must not read it, write it,
-- or be confused for it -- see TimeOutProcessingPaymentsService.
CREATE INDEX idx_payment_intents_processing_since
    ON payment_intents (updated_at)
    WHERE status = 'PROCESSING';
