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

    /**
     * How many users hold PLATFORM_ADMIN platform-wide.
     * <p>
     * {@code r.merchantId is null} is the whole scope test since V23 -- and it is redundant with
     * {@code ck_user_roles_scope}, which already refuses to store PLATFORM_ADMIN any other way.
     * Stated anyway: this count guards the last-admin rule, and a query that would silently start
     * counting merchant-scoped rows if that constraint were ever relaxed is not one to leave
     * implicit.
     */
    @Query("""
        select count(distinct u.userId) from UserJpaEntity u
        join u.roles r
        where r.role = 'PLATFORM_ADMIN' and r.merchantId is null
        """)
    long countPlatformAdmins();
}
