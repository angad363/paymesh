package com.paymesh.risk.application;

import com.paymesh.risk.domain.DenylistEntry;
import com.paymesh.risk.domain.DenylistedEntity;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DenylistRepository {

    DenylistEntry add(DenylistEntry entry);

    /**
     * Whether any live entry matches one of these hashed values.
     * <p>
     * TAKES THE WHOLE CANDIDATE SET IN ONE CALL, not one call per entity type. The evaluation is on
     * the confirm path, inside a transaction that already holds a row lock on the payment intent --
     * two round trips there are two round trips every customer waits for while the lock is held.
     * <p>
     * {@code now} is passed rather than read from the database clock so an expired entry means the
     * same thing here as it does to {@code DenylistEntry.appliesAt}, and so a test can drive it.
     */
    boolean matchesAny(MerchantId merchantId, List<String> hashedValues, Instant now);

    Optional<DenylistEntry> find(MerchantId merchantId, String entryId);

    List<DenylistEntry> findByType(MerchantId merchantId, DenylistedEntity entityType, int limit);

    /** Removing an entry is legitimate -- unlike an assessment, a denylist is a live opinion. */
    boolean remove(MerchantId merchantId, String entryId);
}
