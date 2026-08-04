package com.paymesh.shared.outbox.infrastructure.health;

import com.paymesh.shared.outbox.application.OutboxReader;
import com.paymesh.shared.outbox.application.UnpublishedEvent;
import com.paymesh.shared.outbox.domain.EventId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The alert's rules, with an injected {@link Clock} and no context (ADR-025).
 * <p>
 * Every case below turns on the age of a backlog. With a real clock each one would be a sleep, which
 * is why the indicator takes a {@code Clock} rather than calling {@code Instant.now()}.
 */
class OutboxBacklogHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration ALERT_AGE = Duration.ofMinutes(5);

    /** No backlog at all: the healthy platform, and the one this must never report as broken. */
    @Test
    void reportsUpWhenNothingIsWaiting() {
        Health health = indicatorFor(null, 0).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("oldestUnpublishedAgeSeconds", 0L);
    }

    /**
     * A FEW SECONDS OF BACKLOG IS THE NORMAL STATE, NOT A DEGRADED ONE. Delivery is asynchronous, so
     * at any instant there are usually events in flight. An indicator that flagged those would be red
     * on a healthy platform, and a signal that is always red is the same as no signal.
     */
    @Test
    void reportsUpWhileTheBacklogIsYoungerThanTheThreshold() {
        assertThat(indicatorFor(NOW.minusSeconds(30), 0).health().getStatus()).isEqualTo(Status.UP);
    }

    /**
     * THE RELAY HAS STOPPED, AND THIS IS THE ONLY THING THAT SAYS SO. A relay whose timer is disabled
     * logs nothing and errors on nothing -- it is indistinguishable from a healthy one from every
     * other angle. Only the age of the oldest undelivered event moves. SDD section 24.
     * <p>
     * <b>Sabotage that must turn this red:</b> compare against {@code alertAge} with {@code <}
     * instead of {@code >}, or drop the comparison and always report UP. A stalled relay then looks
     * healthy, which is the state this whole indicator exists to end.
     */
    @Test
    void reportsDownWhenTheOldestUndeliveredEventIsTooOld() {
        Health health = indicatorFor(NOW.minus(Duration.ofMinutes(9)), 0).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("backlogStalled", true)
            .containsEntry("eventsAbandoned", false)
            .containsEntry("oldestUnpublishedAgeSeconds", 540L);
    }

    /**
     * AN ABANDONED EVENT IS AN INCIDENT WITH NO OTHER SIGNAL. The ERROR the relay logged when it gave
     * up scrolled past days ago; the row is still there and still undelivered, and nothing else will
     * ever mention it again.
     * <p>
     * The backlog here is EMPTY -- the dead-lettered row is excluded from the age -- so this proves
     * the second condition stands on its own rather than riding on the first.
     */
    @Test
    void reportsDownWhileAnyEventHasBeenAbandoned() {
        Health health = indicatorFor(null, 2).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("eventsAbandoned", true)
            .containsEntry("backlogStalled", false)
            .containsEntry("deadLetteredEvents", 2L);
    }

    /**
     * The two conditions are named separately because they need different responses: a stalled
     * backlog is usually "the relay is not running", an abandoned event is always "fix the consumer,
     * then requeue these rows". Collapsing them into one boolean costs the first ten minutes of an
     * incident.
     */
    @Test
    void distinguishesTheTwoFailuresWhenBothAreTrue() {
        assertThat(indicatorFor(NOW.minus(Duration.ofHours(1)), 1).health().getDetails())
            .containsEntry("backlogStalled", true)
            .containsEntry("eventsAbandoned", true);
    }

    private static OutboxBacklogHealthIndicator indicatorFor(Instant oldest, long deadLettered) {
        return new OutboxBacklogHealthIndicator(
            new StubReader(new OutboxReader.BacklogHealth(oldest, deadLettered)), ALERT_AGE, CLOCK
        );
    }

    /** Only {@code backlogHealth} is reachable from the indicator; the rest must never be called. */
    private record StubReader(BacklogHealth health) implements OutboxReader {

        @Override
        public List<UnpublishedEvent> findUnpublished(int limit) {
            throw new UnsupportedOperationException("the health indicator must not claim events");
        }

        @Override
        public void markPublished(EventId eventId, Instant publishedAt) {
            throw new UnsupportedOperationException("the health indicator must not publish");
        }

        @Override
        public void recordFailedAttempt(
            String eventId, Instant attemptedAt, String error, int maxAttempts
        ) {
            throw new UnsupportedOperationException("the health indicator must not write");
        }

        @Override
        public BacklogHealth backlogHealth() {
            return health;
        }
    }
}
