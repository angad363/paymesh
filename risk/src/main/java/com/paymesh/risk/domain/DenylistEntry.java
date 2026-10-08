package com.paymesh.risk.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * One thing a merchant refuses to take money from.
 *
 * <h2>THE VALUE IS HASHED, AND THAT IS NOT SECURITY THEATRE</h2>
 *
 * A denylist accumulates exactly the data nobody wants in a support export: the customer who
 * charged back, the device that was used for fraud. Storing the value as a SHA-256 hex digest means
 * a lookup still works -- it is an equality match, which is all a denylist ever does -- while a
 * dump of this table names nobody. It is the same instinct as `api_credentials` storing a hash
 * rather than a key (ADR-022).
 * <p>
 * The cost, stated: <b>there is no "show me what is on my denylist" that returns readable values.</b>
 * An operator can check whether a specific value is denied, and can remove an entry by its
 * {@code dnl_} id, but cannot browse. That is a real limitation and the right trade at this size --
 * the alternative is PII in a table nobody has agreed to encrypt (ADR-006 is still open).
 *
 * @param reason    free text, for the human who has to explain this later. Never interpreted.
 * @param expiresAt when this stops applying, or null for "until someone removes it". A denylist
 *                  with no expiry is a denylist that only ever grows, and most real entries are a
 *                  response to a burst rather than a permanent judgement.
 */
public record DenylistEntry(
    DenylistEntryId entryId,
    MerchantId merchantId,
    DenylistedEntity entityType,
    String hashedValue,
    String reason,
    Instant createdAt,
    Instant expiresAt
) {

    public DenylistEntry {
        if (entryId == null) {
            throw new IllegalArgumentException("Denylist entry identifier cannot be null");
        }

        if (merchantId == null) {
            throw new IllegalArgumentException("Denylist entry merchant cannot be null");
        }

        if (entityType == null) {
            throw new IllegalArgumentException("Denylist entry type cannot be null");
        }

        if (hashedValue == null || hashedValue.isBlank()) {
            throw new IllegalArgumentException("Denylist entry value cannot be blank");
        }

        if (createdAt == null) {
            throw new IllegalArgumentException("Denylist entry creation instant cannot be null");
        }

        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Denylist entry expiry must be after its creation");
        }
    }

    /**
     * Adds an entry, hashing the value on the way in. The raw value is not retained anywhere on
     * this object, so a caller cannot accidentally log it off the aggregate.
     */
    public static DenylistEntry add(
        MerchantId merchantId,
        DenylistedEntity entityType,
        String rawValue,
        String reason,
        Instant createdAt,
        Instant expiresAt
    ) {
        return new DenylistEntry(
            DenylistEntryId.generate(),
            merchantId,
            entityType,
            DenylistHash.of(rawValue),
            reason,
            createdAt,
            expiresAt
        );
    }

    /** An expired entry is still a row; it just no longer denies anything. */
    public boolean appliesAt(Instant instant) {
        return expiresAt == null || expiresAt.isAfter(instant);
    }
}
