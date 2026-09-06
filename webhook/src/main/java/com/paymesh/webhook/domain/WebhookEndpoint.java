package com.paymesh.webhook.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where one merchant wants to be told, and whether PayMesh is still trying.
 *
 * <h2>THE SECRET IS NOT A FIELD, AND THAT IS THE WHOLE DESIGN (ADR-028 §2)</h2>
 *
 * This aggregate holds {@link #secretVersion()}, an integer. The secret is derived from it on
 * demand by {@link WebhookSecrets}. Rotation is an increment; there is nothing to encrypt, nothing
 * to decrypt, and nothing in the database whose leak lets an attacker sign as PayMesh.
 *
 * <h2>ROTATION KEEPS THE OLD VERSION ALIVE FOR A WINDOW</h2>
 *
 * A single signature cannot verify under two secrets, so a merchant who rotates and has not yet
 * deployed their new verifier would see every delivery fail. {@link #signingVersions(Instant)}
 * returns one version normally and two inside the window, and the dispatcher emits a {@code v1=}
 * for each. Stripe does the same thing for the same reason.
 */
public final class WebhookEndpoint {

    /** Bounds the fan-out that runs inside the event dispatcher's transaction. ADR-028 §5. */
    public static final int MAX_ENDPOINTS_PER_MERCHANT = 20;

    /** Consecutive DEAD deliveries, not failed attempts. ADR-028 §6. */
    public static final int DISABLE_AFTER_CONSECUTIVE_FAILURES = 20;

    public static final Duration ROTATION_OVERLAP = Duration.ofHours(24);

    private static final int MAX_URL_LENGTH = 2048;

    private final EndpointId endpointId;
    private final String merchantId;
    private final String url;
    private final int secretVersion;
    private final Integer previousSecretVersion;
    private final Instant previousSecretExpiresAt;
    private final Set<String> subscriptions;
    private final EndpointStatus status;
    private final int consecutiveFailures;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private WebhookEndpoint(
        EndpointId endpointId,
        String merchantId,
        String url,
        int secretVersion,
        Integer previousSecretVersion,
        Instant previousSecretExpiresAt,
        Set<String> subscriptions,
        EndpointStatus status,
        int consecutiveFailures,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.endpointId = endpointId;
        this.merchantId = merchantId;
        this.url = url;
        this.secretVersion = secretVersion;
        this.previousSecretVersion = previousSecretVersion;
        this.previousSecretExpiresAt = previousSecretExpiresAt;
        this.subscriptions = Set.copyOf(subscriptions);
        this.status = status;
        this.consecutiveFailures = consecutiveFailures;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Registers an endpoint at version 1.
     *
     * @throws IllegalArgumentException on a URL that is not plain {@code https}, carries userinfo,
     *     or is over-long, and on an empty subscription list
     */
    public static WebhookEndpoint register(
        String merchantId, String url, List<String> subscriptions, Instant now
    ) {
        return new WebhookEndpoint(
            EndpointId.generate(),
            requireMerchant(merchantId),
            normalizeUrl(url),
            1,
            null,
            null,
            normalizeSubscriptions(subscriptions),
            EndpointStatus.ACTIVE,
            0,
            0L,
            requireTimestamp(now),
            requireTimestamp(now)
        );
    }

    /**
     * Bumps the signing version, keeping the previous one valid for {@link #ROTATION_OVERLAP}.
     *
     * <h2>IDEMPOTENT ON THE VERSION IT IS ROTATING FROM, AND THAT IS NOT A CONVENIENCE</h2>
     *
     * The caller names the version it believes is current. Asked to rotate from a version that is
     * already spent, this returns the aggregate unchanged so a retried request re-derives the same
     * secret instead of bumping again.
     * <p>
     * That property is what lets this route stay off the {@code IdempotencyFilter}, and it has to:
     * {@code idempotency_records.response_body} persists response bodies verbatim so a retry can
     * replay them, so registering a route that returns a secret would write that secret to the
     * database in cleartext -- one table away from the storage this whole design exists to avoid.
     */
    public WebhookEndpoint rotateSecret(int fromVersion, Instant now) {
        if (fromVersion != secretVersion) {
            if (fromVersion == secretVersion - 1) {
                return this;
            }

            throw new SecretVersionMismatchException(endpointId, fromVersion, secretVersion);
        }

        return copy(
            secretVersion + 1,
            secretVersion,
            requireTimestamp(now).plus(ROTATION_OVERLAP),
            subscriptions,
            status,
            consecutiveFailures,
            now
        );
    }

    /**
     * The versions a payload should be signed under right now: one normally, two inside a rotation
     * window. Ordered current-first, because that is the one a caught-up merchant checks first.
     */
    public List<Integer> signingVersions(Instant now) {
        if (previousSecretVersion == null || !now.isBefore(previousSecretExpiresAt)) {
            return List.of(secretVersion);
        }

        return List.of(secretVersion, previousSecretVersion);
    }

    public WebhookEndpoint resubscribe(List<String> newSubscriptions, Instant now) {
        return copy(
            secretVersion,
            previousSecretVersion,
            previousSecretExpiresAt,
            normalizeSubscriptions(newSubscriptions),
            status,
            consecutiveFailures,
            now
        );
    }

    /** Reversible, whether the merchant asked or the failure threshold did. */
    public WebhookEndpoint disable(Instant now) {
        return copy(
            secretVersion, previousSecretVersion, previousSecretExpiresAt,
            subscriptions, EndpointStatus.DISABLED, consecutiveFailures, now
        );
    }

    /** Re-enabling clears the failure count, so a recovered endpoint starts from zero. */
    public WebhookEndpoint enable(Instant now) {
        return copy(
            secretVersion, previousSecretVersion, previousSecretExpiresAt,
            subscriptions, EndpointStatus.ACTIVE, 0, now
        );
    }

    /**
     * Records that one delivery exhausted its whole retry budget, disabling at the threshold.
     *
     * <p><b>One dead delivery counts as one</b>, not as its five spent attempts. The difference is
     * a factor of five in when an endpoint disables, so it is decided here rather than left to
     * whoever writes the caller.
     */
    public WebhookEndpoint recordDeadDelivery(Instant now) {
        int failures = consecutiveFailures + 1;

        EndpointStatus next = failures >= DISABLE_AFTER_CONSECUTIVE_FAILURES
            ? EndpointStatus.DISABLED
            : status;

        return copy(
            secretVersion, previousSecretVersion, previousSecretExpiresAt,
            subscriptions, next, failures, now
        );
    }

    /** Any success clears the streak. An endpoint that works intermittently is not disabled. */
    public WebhookEndpoint recordSuccess(Instant now) {
        if (consecutiveFailures == 0) {
            return this;
        }

        return copy(
            secretVersion, previousSecretVersion, previousSecretExpiresAt,
            subscriptions, status, 0, now
        );
    }

    public boolean isSubscribedTo(String eventType) {
        return subscriptions.contains(eventType);
    }

    public boolean isActive() {
        return status == EndpointStatus.ACTIVE;
    }

    // --- normalization and invariants -----------------------------------------------------------

    /**
     * HTTPS ONLY, AND NO USERINFO.
     *
     * <p>{@code ck_webhook_endpoints_url_https} enforces the scheme in the database, which is where
     * it belongs. What a regex cannot do is see credentials: {@code https://user:pass@internal/}
     * passes any prefix test, and is a plausible way to smuggle a request somewhere it should not
     * go. Parsing is the application's job precisely because the constraint cannot do it.
     */
    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("A webhook endpoint URL is required");
        }

        String trimmed = url.trim();

        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                "A webhook endpoint URL cannot exceed " + MAX_URL_LENGTH + " characters"
            );
        }

        URI uri;

        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("A webhook endpoint URL must be a valid URI", exception);
        }

        if (uri.getScheme() == null
            || !uri.getScheme().toLowerCase(Locale.ROOT).equals("https")) {
            throw new IllegalArgumentException(
                "A webhook endpoint URL must use https, so the payload is not sent in clear"
            );
        }

        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                "A webhook endpoint URL must not carry credentials in its userinfo"
            );
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("A webhook endpoint URL must name a host");
        }

        return trimmed;
    }

    /**
     * At least one type, de-duplicated, order preserved for a stable response.
     * <p>
     * An endpoint subscribed to nothing is not a configuration, it is a mistake that looks like
     * one: it would sit ACTIVE and silently receive nothing forever.
     */
    private static Set<String> normalizeSubscriptions(List<String> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            throw new IllegalArgumentException(
                "A webhook endpoint must subscribe to at least one event type"
            );
        }

        Set<String> normalized = new LinkedHashSet<>();

        for (String subscription : subscriptions) {
            if (subscription == null || subscription.isBlank()) {
                throw new IllegalArgumentException("A subscribed event type cannot be blank");
            }

            normalized.add(subscription.trim());
        }

        return normalized;
    }

    private static String requireMerchant(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("A webhook endpoint belongs to a merchant");
        }

        return merchantId;
    }

    private static Instant requireTimestamp(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("A timestamp is required");
        }

        return now;
    }

    private WebhookEndpoint copy(
        int newSecretVersion,
        Integer newPreviousVersion,
        Instant newPreviousExpiry,
        Set<String> newSubscriptions,
        EndpointStatus newStatus,
        int newConsecutiveFailures,
        Instant now
    ) {
        return new WebhookEndpoint(
            endpointId, merchantId, url,
            newSecretVersion, newPreviousVersion, newPreviousExpiry,
            newSubscriptions, newStatus, newConsecutiveFailures,
            version, createdAt, requireTimestamp(now)
        );
    }

    /** Rebuilds from persistence. No invariants re-run: the database already holds them. */
    public static WebhookEndpoint rehydrate(
        EndpointId endpointId,
        String merchantId,
        String url,
        int secretVersion,
        Integer previousSecretVersion,
        Instant previousSecretExpiresAt,
        Set<String> subscriptions,
        EndpointStatus status,
        int consecutiveFailures,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new WebhookEndpoint(
            endpointId, merchantId, url, secretVersion, previousSecretVersion,
            previousSecretExpiresAt, subscriptions, status, consecutiveFailures,
            version, createdAt, updatedAt
        );
    }

    public EndpointId endpointId() {
        return endpointId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String url() {
        return url;
    }

    public int secretVersion() {
        return secretVersion;
    }

    public Integer previousSecretVersion() {
        return previousSecretVersion;
    }

    public Instant previousSecretExpiresAt() {
        return previousSecretExpiresAt;
    }

    public Set<String> subscriptions() {
        return subscriptions;
    }

    public EndpointStatus status() {
        return status;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
