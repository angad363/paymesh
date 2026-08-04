-- ============================================================================
--  V21__add_outbox_delivery_attempts.sql
--  Gives the outbox relay a memory. SDD section 24, ADR-025.
--
--  THE DEFECT THIS CLOSES. Since ADR-016 the relay has retried a failed event at
--  the head of every pass, forever, and deferred that aggregate's later events
--  behind it to preserve ordering. That is correct while the failure is
--  transient and catastrophic when it is not: a permanently failing event
--  freezes its own aggregate's event stream for good. Open item 14 in
--  docs/project-status.md called this "the largest known hole in event delivery",
--  and the reason it stayed open is that the relay had nowhere to write down what
--  it had already tried. Every pass started from zero knowledge, so "this has
--  failed 900 times" and "this has failed once" were indistinguishable.
--
--  WHY COLUMNS AND NOT A DEAD-LETTER TABLE. The obvious shape is a second table
--  that failed events are MOVED to. It was refused for the reason V7 refused a
--  status column: the move is a second copy of a fact this table already holds,
--  and two rows describing one event can disagree. A moved row also loses its
--  place in occurred_at order, so requeueing it would deliver an aggregate's
--  events out of sequence -- the exact guarantee the relay defers events to
--  protect. Here the row never moves: dead_lettered_at IS NULL is the same
--  NULL-means-pending status model published_at already uses, requeueing is
--  `SET dead_lettered_at = NULL` and the event resumes in its original position.
--
--  ALL FOUR COLUMNS ARE NULLABLE OR DEFAULTED, so this migration cannot fail on
--  an existing backlog: every row already in the table becomes "never attempted,
--  never dead-lettered", which is exactly what it is.
-- ============================================================================

ALTER TABLE outbox_events

    -- How many delivery attempts have failed. NOT the number of attempts made:
    -- an attempt that succeeds stamps published_at and never comes back here, so
    -- this counter only ever counts failures. DEFAULT 0 rather than NULL because
    -- "no failures yet" and "unknown" are not different states, and a nullable
    -- counter would make every comparison against max-attempts a three-valued
    -- one.
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,

    -- When the most recent failure happened, per the application's Clock. No DB
    -- DEFAULT and no trigger: the application stays the source of time
    -- everywhere in this schema, which is what lets a test drive the retry
    -- budget with a fixed clock instead of sleeping.
    ADD COLUMN last_attempt_at TIMESTAMP WITH TIME ZONE,

    -- The most recent failure's message, for the human who has to answer "why is
    -- this stuck". TEXT and not VARCHAR(n): an exception message has no natural
    -- bound, and a truncating cast would silently remove the end of the message
    -- -- which, for a stack-trace-derived string, is the part that names the
    -- cause. Only the LATEST message is kept. A full attempt log would be a
    -- child table for a question nobody has asked yet.
    ADD COLUMN last_error TEXT,

    -- When the relay gave up. NULL MEANS STILL BEING RETRIED, AND THAT IS THE
    -- WHOLE STATUS MODEL -- deliberately the same shape as published_at above.
    --
    -- A dead-lettered event is NOT deleted and NOT delivered. It is retained
    -- exactly as written, excluded from the claim query so it stops blocking its
    -- aggregate, and left for an operator. That is a real loss of delivery and
    -- it is the lesser one: the alternative is an aggregate whose every
    -- subsequent event is withheld indefinitely because of one poisoned
    -- predecessor.
    ADD COLUMN dead_lettered_at TIMESTAMP WITH TIME ZONE,

    -- A negative failure count is corruption, not a state. Cheap, and it makes
    -- an arithmetic mistake in the relay's UPDATE fail loudly at the database
    -- rather than quietly disabling the max-attempts comparison.
    ADD CONSTRAINT ck_outbox_events_attempt_count CHECK (attempt_count >= 0);

-- THE CLAIM QUERY CHANGED, SO ITS INDEX MUST. V7 built this partial index for
-- "oldest unpublished first"; the relay now also skips dead-lettered rows, and a
-- partial index whose predicate is narrower than the query's still forces a
-- filter on every row it returns. Rebuilt so the index holds exactly the
-- deliverable backlog -- which is the property V7 wanted: it shrinks back to
-- nothing once the relay keeps up, instead of retaining every event the relay
-- has permanently given up on.
DROP INDEX idx_outbox_events_unpublished;

CREATE INDEX idx_outbox_events_unpublished ON outbox_events (occurred_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;

-- The operator's index, and the only other one this table gets. Answers "what
-- did the relay give up on", which is the question the health indicator raises
-- and the one a human then has to act on. Partial, so it costs nothing on the
-- overwhelmingly common path where the relay has given up on nothing.
CREATE INDEX idx_outbox_events_dead_lettered ON outbox_events (dead_lettered_at)
    WHERE dead_lettered_at IS NOT NULL;
