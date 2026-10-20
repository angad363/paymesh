package com.paymesh.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the append-only security audit trail (SDD 8.4). Written on every
 * authentication outcome, read by nothing in the request path -- it exists for
 * monitoring and incident review, and will later be the source of the
 * identity.login.failed event.
 *
 * <p>Recording is best-effort by intent: an audit write must never be the reason a
 * legitimate login fails. The application layer keeps it inside the same
 * transaction as the state change it describes, so the two cannot disagree.
 */
public record SecurityEvent(
    String eventId,
    SecurityEventType type,
    String actor,
    String ipHash,
    Instant occurredAt
) {

    public SecurityEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Security event identifier cannot be blank");
        }

        if (type == null) {
            throw new IllegalArgumentException("Security event type cannot be null");
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("Security event timestamp cannot be null");
        }
    }

    /**
     * @param actor     the user id when the account is known, otherwise the email that
     *                  was attempted. A failed login against an unknown address is
     *                  precisely the event worth keeping.
     * @param ipAddress the caller's raw address, hashed here and never stored raw.
     *                  May be null when the address is unavailable.
     */
    public static SecurityEvent record(
        SecurityEventType type,
        String actor,
        String ipAddress,
        Instant occurredAt
    ) {
        return new SecurityEvent(
            UUID.randomUUID().toString(),
            type,
            actor,
            ipAddress == null || ipAddress.isBlank() ? null : Sha256.hex(ipAddress),
            occurredAt
        );
    }
}
