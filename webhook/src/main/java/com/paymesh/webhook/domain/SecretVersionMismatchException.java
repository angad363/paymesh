package com.paymesh.webhook.domain;

/**
 * Rotation was asked to start from a version that is neither current nor the one just replaced.
 *
 * <p>Two clients rotating at once, or a caller working from a stale read. Refused rather than
 * applied, because the alternative -- treating rotate as "increment whatever is there" -- makes a
 * retried request bump twice and permanently invalidate the secret the merchant was handed in the
 * first response.
 */
public class SecretVersionMismatchException extends RuntimeException {

    private final transient EndpointId endpointId;
    private final int requestedFromVersion;
    private final int actualVersion;

    public SecretVersionMismatchException(
        EndpointId endpointId, int requestedFromVersion, int actualVersion
    ) {
        super(
            "Cannot rotate endpoint " + endpointId.value() + " from version " + requestedFromVersion
                + ": it is at version " + actualVersion
        );

        this.endpointId = endpointId;
        this.requestedFromVersion = requestedFromVersion;
        this.actualVersion = actualVersion;
    }

    public EndpointId endpointId() {
        return endpointId;
    }

    public int requestedFromVersion() {
        return requestedFromVersion;
    }

    public int actualVersion() {
        return actualVersion;
    }
}
