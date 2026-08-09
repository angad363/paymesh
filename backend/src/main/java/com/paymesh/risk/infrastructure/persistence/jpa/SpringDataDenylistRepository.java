package com.paymesh.risk.infrastructure.persistence.jpa;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataDenylistRepository
    extends JpaRepository<DenylistEntryJpaEntity, String> {

    /**
     * THE EVALUATION QUERY. One round trip for every candidate value on the payment, because this
     * runs inside the confirm transaction while the payment intent's row lock is held -- a query
     * per entity type would be a second wait for every customer.
     * <p>
     * Counts rather than selects: the caller only asks whether anything matched, and returning the
     * rows would mean mapping entries nobody reads. Expiry is compared against the caller's clock
     * rather than {@code now()} so an expired entry means the same thing here as it does to
     * {@code DenylistEntry.appliesAt}, and so a test can drive it.
     */
    @Query("""
        select count(d) from DenylistEntryJpaEntity d
        where d.merchantId = :merchantId
          and d.hashedValue in :hashedValues
          and (d.expiresAt is null or d.expiresAt > :now)
        """)
    long countLiveMatches(
        @Param("merchantId") String merchantId,
        @Param("hashedValues") List<String> hashedValues,
        @Param("now") Instant now
    );

    Optional<DenylistEntryJpaEntity> findByMerchantIdAndEntryId(String merchantId, String entryId);

    List<DenylistEntryJpaEntity> findByMerchantIdAndEntityTypeOrderByCreatedAtDesc(
        String merchantId, String entityType, Limit limit
    );

    long deleteByMerchantIdAndEntryId(String merchantId, String entryId);
}
