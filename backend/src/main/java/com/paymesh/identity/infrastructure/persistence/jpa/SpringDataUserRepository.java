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
     * Locks ONE user's row in {@code users}, and nothing else.
     *
     * <h2>WHY NOT {@code @Lock(PESSIMISTIC_WRITE)}, WHICH IS THE HOUSE IDIOM</h2>
     *
     * Every other pessimistic lock in this codebase is a {@code @Lock} on a Spring Data method
     * ({@code SpringDataOrderRepository}, {@code SpringDataPaymentIntentRepository}, and three
     * more). That does not work here: {@code UserJpaEntity.roles} is an EAGER
     * {@code @ElementCollection}, so a query returning the entity renders a left outer join to
     * {@code user_roles}, and PostgreSQL refuses {@code FOR UPDATE} on the nullable side of an
     * outer join. Returning a scalar sidesteps the join entirely and locks exactly the row meant.
     *
     * <h2>WHY LOCK THE PARENT ROW AT ALL, WHEN THE GUARD COUNTS CHILD ROWS</h2>
     *
     * Lock ordering. Hibernate flushes the element collection as delete-all-and-recreate, so every
     * writer of this aggregate takes {@code users} before {@code user_roles}. A demotion that took
     * {@code user_roles} first (via the count below) and {@code users} second would invert that
     * against all six sibling writers and deadlock -- 40P01, surfacing as a 500 to whichever side
     * PostgreSQL picks. Taking this lock first puts the demotion back in the same order as
     * everybody else.
     *
     * @return {@code 1} when the row exists and is now locked, {@code null} when there is no such
     *     user -- the caller reads the user separately and reports the absence
     */
    @Query(value = "select 1 from users where user_id = :userId for update", nativeQuery = true)
    Integer lockUserRow(@Param("userId") String userId);

    /**
     * How many users hold PLATFORM_ADMIN platform-wide, with every one of those rows locked.
     *
     * <h2>THE LOCK IS THE POINT, NOT THE COUNT</h2>
     *
     * The last-admin guard reads this and then deletes, and READ COMMITTED does not make that
     * pair atomic: two concurrent demotions of the last two admins both read 2, both pass, both
     * commit, and the platform is left with none -- the exact dead end
     * {@code LastPlatformAdminException} exists to prevent. So the database contributes a lock: the
     * second transaction blocks here until the first commits, then re-reads and sees 1.
     *
     * <h2>WHY A LOCK AND NOT A CONSTRAINT, WHICH IS THE HOUSE PREFERENCE</h2>
     *
     * A {@code CHECK} cannot express "at least one row must remain" -- it sees one row. The repo's
     * other cross-row invariants (debits equal credits in V15, refunds within captured in V16) use
     * a DEFERRED constraint trigger instead, and one could be written here. It would not close this
     * race: a deferred trigger fires inside the committing transaction, under its own snapshot, so
     * two concurrent demotions each still see the other's victim standing and each still pass.
     * Serialising the readers is the part that has to happen, and only a lock does that. A trigger
     * on top would be a second, weaker copy of a guard the lock already makes true.
     * <p>
     * Native rather than JPQL because JPA has no portable {@code FOR UPDATE} on an aggregate, and
     * PostgreSQL refuses the two at the same query level -- hence the lock in a subquery with the
     * count outside it. {@code order by user_id} so two demotions take the rows in the same order
     * and block rather than deadlock.
     * <p>
     * <b>Only meaningful inside a transaction</b>, and only after {@link #lockUserRow} -- a row
     * lock taken outside a transaction is released at the end of the statement, and taken before
     * the {@code users} row it inverts the lock order every other writer of this aggregate uses.
     * <p>
     * <b>It locks nothing when there are none.</b> {@code FOR UPDATE} holds matching rows, and an
     * empty result matches none; PostgreSQL has no gap lock under READ COMMITTED. So this
     * serialises demotions, where rows exist, and does nothing for the bootstrap's "are there
     * none" read. That read is protected by {@code uq_user_roles_platform_scoped} instead: two
     * instances bootstrapping the same email at once means one insert loses and that instance
     * fails to start, then succeeds on restart because an admin now exists.
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
