package com.paymesh.merchant.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataApiCredentialRepository
    extends JpaRepository<ApiCredentialJpaEntity, String> {

    Optional<ApiCredentialJpaEntity> findByPublicPrefix(String publicPrefix);

    Optional<ApiCredentialJpaEntity> findByMerchantIdAndApiCredentialId(
        String merchantId, String apiCredentialId
    );

    List<ApiCredentialJpaEntity> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    /**
     * A targeted UPDATE rather than a read-modify-write.
     * <p>
     * Loading the entity to set one timestamp would take a managed lock on the row for the rest of
     * the request -- on the row every single API call for that key touches. This writes the column
     * and nothing else.
     */
    @Modifying
    // REQUIRES_NEW here rather than on the adapter: the adapter is a final class (the codebase
    // convention for hand-wired beans) and Spring cannot CGLIB-proxy a final class, so an
    // @Transactional there silently fails to start the context. An interface is JDK-proxied and has
    // no such problem.
    //
    // A separate transaction so a bookkeeping write cannot enlist in -- or poison -- whatever the
    // request goes on to do. It also needs SOME transaction: a @Modifying query without one throws.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update ApiCredentialJpaEntity c set c.lastUsedAt = :usedAt where c.apiCredentialId = :id")
    int touchLastUsed(@Param("id") String apiCredentialId, @Param("usedAt") Instant usedAt);
}
