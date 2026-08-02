package com.paymesh.identity.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data access to the users table.
 * findById(String) is inherited from JpaRepository, so it is not redeclared here.
 */
public interface SpringDataUserRepository extends JpaRepository<UserJpaEntity, String> {

    Optional<UserJpaEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Everyone holding any role at this merchant.
     * <p>
     * A join into the {@code user_roles} element collection -- DISTINCT because a user holding two
     * roles at one merchant would otherwise appear twice, and an admin listing their staff should
     * see each person once.
     */
    @Query("""
        select distinct u from UserJpaEntity u
        join u.roles r
        where r.merchantId = :merchantId
        order by u.createdAt
        """)
    java.util.List<UserJpaEntity> findByMerchant(@Param("merchantId") String merchantId);
}
