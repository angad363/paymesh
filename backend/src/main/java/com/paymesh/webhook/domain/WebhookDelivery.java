package com.paymesh.webhook.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One attempt to tell one merchant one thing, and everything known about how it went.
 *
 * <h2>THE RETRY BUDGET IS NOT ADR-025'S, ON PURPOSE (ADR-028 §6)</h2>
 *
 * The outbox relay's budget is tuned for in-process work that should always succeed, so a repeated
 * failure there is a bug. Here a merchant returning 503 for six hours is <b>ordinary operation</b>.
 * The schedule below spans <b>8h36m</b> across six attempts: long enough to survive a merchant's
 * overnight deploy or an outage in a timezone where nobody is awake, and short of a day so a dead
 * endpoint does not hold rows indefinitely.
 *
 * <h2>THE COUNTER HERE IS NOT THE COUNTER ON THE ENDPOINT</h2>
 *
 * {@code attempts} counts tries against <i>this</i> delivery, capped by the schedule.
 * {@code WebhookEndpoint.consecutiveFailures} counts <i>dead deliveries</i> -- ones that spent the
 * whole budget. One dead delivery moves that one by one, not by five. Confusing them is a factor of
 * five in when an endpoint disables.
 */
public final class WebhookDelivery {

    /**
     * The wait before each retry. Index n is the wait between attempt n+1 and attempt n+2.
     * <p>
     * Not exponential-by-formula but a written list, because the numbers were chosen for what they
     * cover rather than for a curve, and a list can be read against the reasoning in ADR-028 §6.
     */
    private static final List<Duration> BACKOFF = List.of(
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        Duration.ofMinutes(30),
        Duration.ofHours(2),
        Duration.ofHours(6)
    );

    /**
     * SIZE PLUS ONE, AND THE PLUS ONE IS NOT A FENCEPOST TO TIDY AWAY. n attempts have n-1 waits
     * between them, so five waits carry six attempts.
     * <p>
     * <b>This was {@code BACKOFF.size()} and it was wrong.</b> The last entry was never reached --
     * {@code attemptFailed} declared the delivery dead on the fifth attempt, before ever indexing
     * the six-hour wait -- so the horizon was 1m + 5m + 30m + 2h = 2h36m rather than the 8h36m that
     * the class javadoc above, V25's header, {@code application.yaml} and ADR-028 §6 all state. A
     * merchant down for an overnight deploy, which is the case those numbers were chosen for, lost
     * their deliveries about three times too early, and the endpoint's disable streak advanced
     * about three times too fast.
     */
    public static final int MAX_ATTEMPTS = BACKOFF.size() + 1;

    /** Enough of the merchant's answer to debug with, and no more. The read is capped too. */
    public static final int MAX_RESPONSE_EXCERPT = 512;

    private final WebhookDeliveryId deliveryId;
    private final WebhookEventId webhookEventId;
    private final EndpointId endpointId;
    private final String merchantId;
    private final DeliveryStatus status;
    private final int attempts;
    private final Instant nextAttemptAt;
    private final Integer lastStatusCode;
    private final String lastResponseExcerpt;
    private final Instant createdAt;
    private final Instant updatedAt;

    private WebhookDelivery(
        WebhookDeliveryId deliveryId,
        WebhookEventId webhookEventId,
        EndpointId endpointId,
        String merchantId,
        DeliveryStatus status,
        int attempts,
        Instant nextAttemptAt,
        Integer lastStatusCode,
        String lastResponseExcerpt,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.deliveryId = deliveryId;
        this.webhookEventId = webhookEventId;
        this.endpointId = endpointId;
        this.merchantId = merchantId;
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lastStatusCode = lastStatusCode;
        this.lastResponseExcerpt = lastResponseExcerpt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Queued for immediate sending. The first attempt does not wait. */
    public static WebhookDelivery queue(
        WebhookEventId webhookEventId, EndpointId endpointId, String merchantId, Instant now
    ) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("A delivery belongs to a merchant");
        }

        if (now == null) {
            throw new IllegalArgumentException("A timestamp is required");
        }

        return new WebhookDelivery(
            WebhookDeliveryId.generate(), webhookEventId, endpointId, merchantId,
            DeliveryStatus.PENDING, 0, now, null, null, now, now
        );
    }

    /** The endpoint answered 2xx. Terminal, and the schedule is cleared with it. */
    public WebhookDelivery succeeded(int statusCode, String responseExcerpt, Instant now) {
        return new WebhookDelivery(
            deliveryId, webhookEventId, endpointId, merchantId,
            DeliveryStatus.DELIVERED, attempts + 1, null,
            statusCode, excerpt(responseExcerpt), createdAt, now
        );
    }

    /**
     * The attempt did not land. Reschedules, or gives up once the budget is spent.
     *
     * @param statusCode null when nothing answered -- a refused connection or a timeout has no
     *     status, which is itself informative and is why the column is nullable
     */
    public WebhookDelivery attemptFailed(Integer statusCode, String responseExcerpt, Instant now) {
        int attempted = attempts + 1;

        if (attempted >= MAX_ATTEMPTS) {
            return new WebhookDelivery(
                deliveryId, webhookEventId, endpointId, merchantId,
                DeliveryStatus.FAILED, attempted, null,
                statusCode, excerpt(responseExcerpt), createdAt, now
            );
        }

        return new WebhookDelivery(
            deliveryId, webhookEventId, endpointId, merchantId,
            DeliveryStatus.PENDING, attempted, now.plus(BACKOFF.get(attempted - 1)),
            statusCode, excerpt(responseExcerpt), createdAt, now
        );
    }

    /**
     * Re-queues a terminal delivery, resetting the budget.
     *
     * <p>The payload is untouched -- it lives on {@link WebhookEvent} and is immutable in the
     * database -- so a replay resends byte-identical content. The signature will differ, because
     * the timestamp inside the signed string is fresh; asserting otherwise would be asserting a bug.
     *
     * @throws DeliveryNotReplayableException while it is still {@code PENDING}, because replaying a
     *     delivery the dispatcher is about to retry means two POSTs for one event
     */
    public WebhookDelivery replay(Instant now) {
        if (status == DeliveryStatus.PENDING) {
            throw new DeliveryNotReplayableException(deliveryId);
        }

        return new WebhookDelivery(
            deliveryId, webhookEventId, endpointId, merchantId,
            DeliveryStatus.PENDING, 0, now,
            lastStatusCode, lastResponseExcerpt, createdAt, now
        );
    }

    /** Spent its whole budget. What increments the endpoint's streak by exactly one. */
    public boolean isDead() {
        return status == DeliveryStatus.FAILED;
    }

    public boolean isPending() {
        return status == DeliveryStatus.PENDING;
    }

    private static String excerpt(String responseExcerpt) {
        if (responseExcerpt == null || responseExcerpt.isBlank()) {
            return null;
        }

        String trimmed = responseExcerpt.strip();

        return trimmed.length() <= MAX_RESPONSE_EXCERPT
            ? trimmed
            : trimmed.substring(0, MAX_RESPONSE_EXCERPT);
    }

    public static WebhookDelivery rehydrate(
        WebhookDeliveryId deliveryId,
        WebhookEventId webhookEventId,
        EndpointId endpointId,
        String merchantId,
        DeliveryStatus status,
        int attempts,
        Instant nextAttemptAt,
        Integer lastStatusCode,
        String lastResponseExcerpt,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new WebhookDelivery(
            deliveryId, webhookEventId, endpointId, merchantId, status, attempts,
            nextAttemptAt, lastStatusCode, lastResponseExcerpt, createdAt, updatedAt
        );
    }

    public WebhookDeliveryId deliveryId() {
        return deliveryId;
    }

    public WebhookEventId webhookEventId() {
        return webhookEventId;
    }

    public EndpointId endpointId() {
        return endpointId;
    }

    public String merchantId() {
        return merchantId;
    }

    public DeliveryStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public Integer lastStatusCode() {
        return lastStatusCode;
    }

    public String lastResponseExcerpt() {
        return lastResponseExcerpt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
