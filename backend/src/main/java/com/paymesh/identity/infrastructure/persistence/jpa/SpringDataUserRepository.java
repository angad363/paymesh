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
     * How many users hold PLATFORM_ADMIN platform-wide, with every one of those rows locked.
     *
     * <h2>THE LOCK IS THE POINT, NOT THE COUNT</h2>
     *
     * The last-admin guard reads this and then deletes, and READ COMMITTED does not make that
     * pair atomic: two concurrent demotions of the last two admins both read 2, both pass, both
     * commit, and the platform is left with none -- the exact dead end
     * {@code LastPlatformAdminException} exists to prevent. No CHECK constraint can express "at
     * least one row must remain" across rows, so the database contributes a lock instead: the
     * second transaction blocks here until the first commits, then re-reads and sees 1.
     * <p>
     * Native rather than JPQL because JPA has no portable {@code FOR UPDATE} on an aggregate, and
     * PostgreSQL refuses the two at the same query level -- hence the lock in a subquery with the
     * count outside it. {@code order by user_id} so two demotions take the rows in the same order
     * and block rather than deadlock.
     * <p>
     * <b>Only meaningful inside a transaction</b> -- a row lock taken outside one is released at
     * the end of the statement. Both callers run in {@code TransactionTemplate}.
     * <p>
     * {@code merchant_id is null} is the whole scope test since V23, and it is redundant with
     * {@code ck_user_roles_scope}. Stated anyway: a query that would silently start counting
     * merchant-scoped rows if that constraint were ever relaxed is not one to leave implicit.
     */
    @Query(value = """
        select count(*) from (
            select 1 from user_roles
             where role = 'PLATFORM_ADMIN' and merchant_id is null
             order by user_id
             for update
        ) locked
        """, nativeQuery = true)
    long countPlatformAdminsForUpdate();
}
