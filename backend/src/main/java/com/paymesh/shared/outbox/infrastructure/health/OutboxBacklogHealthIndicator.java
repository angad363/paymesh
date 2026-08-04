package com.paymesh.shared.outbox.infrastructure.health;

import com.paymesh.shared.outbox.application.OutboxReader;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The alert half of event delivery: says out loud when events are not getting through (ADR-025).
 *
 * <h2>WHY THIS IS THE ALERT, RATHER THAN METRICS</h2>
 *
 * SDD section 26 wants OpenTelemetry, Prometheus and Grafana. None of it exists, and open item 14
 * has stood unclosed partly because "add an alert" was read as "first build an observability stack".
 * It is not: {@code /actuator/health} is already exposed (see {@code application.yaml}'s
 * {@code management.endpoints.web.exposure.include}), it is already the endpoint anything watching
 * this application polls, and a health contributor is the framework's own answer to "surface a
 * degraded subsystem". Building a metrics pipeline to carry two numbers would be building the
 * pipeline, not the alert.
 * <p>
 * When Prometheus does land, the two numbers below become gauges and this class stays -- a gauge
 * nobody has written a rule for is not an alert either.
 *
 * <h2>THE TWO CONDITIONS ARE DIFFERENT PROBLEMS AND BOTH ARE REPORTED</h2>
 *
 * <ul>
 *   <li><b>Dead-lettered events exist.</b> The relay has permanently given up on a committed state
 *       change: a payment succeeded, a refund completed, and no consumer will ever hear about it
 *       until a human requeues the row. This does not heal on its own and no other signal exists
 *       for it, because the ERROR the relay logged scrolled past days ago.</li>
 *   <li><b>The backlog is old.</b> {@code min(occurred_at)} over deliverable rows is SDD section
 *       24's "oldest unpublished event age" -- the one number that separates "the relay is keeping
 *       up" from "the relay stopped running an hour ago and nothing noticed". A relay whose timer
 *       is disabled looks exactly like a healthy one from every other angle: no errors, no logs,
 *       nothing. Only the age moves.</li>
 * </ul>
 *
 * Both are reported as DOWN rather than one being softened, because their consequence is the same:
 * a merchant's order sits at PENDING for a payment that succeeded, and the balance the Ledger should
 * have posted is not there.
 *
 * <h2>The sharp edge, stated here rather than discovered during an incident</h2>
 *
 * DOWN makes the aggregate {@code /actuator/health} 503. That is the intent -- it is what makes this
 * an alert instead of a field in a JSON body nobody reads -- but it means <b>this indicator must
 * never be wired into a Kubernetes liveness or readiness probe</b> when SDD section 27's deployment
 * work lands. Restarting the application does not deliver a dead-lettered event, and draining an
 * instance because its backlog is old removes the very process that was working through the
 * backlog. It belongs in a custom health group that alerting scrapes and orchestration ignores.
 * Recorded as an open item in {@code docs/project-status.md}.
 *
 * <h2>It is a plain object</h2>
 *
 * No {@code @Component}. {@code OutboxConfiguration} declares it as a {@code @Bean}, per section 13
 * of the coding conventions, and Boot registers any {@code HealthIndicator} bean it finds. The
 * {@link Clock} is injected, so a test can put the backlog an hour in the past without sleeping.
 */
public final class OutboxBacklogHealthIndicator implements HealthIndicator {

    private final OutboxReader reader;
    private final Duration alertAge;
    private final Clock clock;

    public OutboxBacklogHealthIndicator(OutboxReader reader, Duration alertAge, Clock clock) {
        this.reader = reader;
        this.alertAge = alertAge;
        this.clock = clock;
    }

    @Override
    public Health health() {
        OutboxReader.BacklogHealth backlog = reader.backlogHealth();

        Instant oldest = backlog.oldestUnpublished();
        Duration age = oldest == null ? Duration.ZERO : Duration.between(oldest, Instant.now(clock));

        // A backlog younger than the threshold is not merely tolerated, it is the NORMAL state:
        // delivery is asynchronous, so at any instant there are usually a few seconds of events in
        // flight. Reporting those as degraded would make the healthy state red.
        boolean stalled = oldest != null && age.compareTo(alertAge) > 0;
        boolean abandoned = backlog.deadLettered() > 0;

        Health.Builder health = stalled || abandoned ? Health.down() : Health.up();

        return health
            .withDetail("oldestUnpublishedAgeSeconds", age.toSeconds())
            .withDetail("alertAgeSeconds", alertAge.toSeconds())
            .withDetail("deadLetteredEvents", backlog.deadLettered())
            // Named rather than inferred from the two booleans by whoever is reading this at 3am.
            // "backlogStalled" and "eventsAbandoned" need different responses: the first is usually
            // "the relay is not running", the second is always "requeue these rows after fixing the
            // consumer", and confusing them wastes the first ten minutes of an incident.
            .withDetail("backlogStalled", stalled)
            .withDetail("eventsAbandoned", abandoned)
            .build();
    }
}
