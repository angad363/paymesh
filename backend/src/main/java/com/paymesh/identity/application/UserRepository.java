package com.paymesh.identity.application;

import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;

import java.util.Optional;

public interface UserRepository {

    boolean existsByEmail(String normalizedEmail);

    User save(User user);

    Optional<User> findByEmail(String normalizedEmail);

    Optional<User> findByUserId(UserId userId);

    /**
     * The same read, holding this user's row against concurrent change until the calling
     * transaction ends. <b>Call it inside a transaction</b> or the lock is gone before it is used.
     *
     * <p>Two reasons, and the second is the one that is easy to miss. It makes a read-then-write
     * see the truth rather than a snapshot from before the winner committed. And it fixes the
     * ORDER in which locks are taken -- every writer of this aggregate touches the user row before
     * its roles, so a caller that locks roles first would deadlock against all of them.
     */
    Optional<User> findByUserIdForUpdate(UserId userId);

    /** Everyone holding any role at this merchant. What an admin needs before revoking anybody. */
    java.util.List<User> findByMerchant(com.paymesh.shared.tenant.MerchantId merchantId);

    /**
     * How many users hold PLATFORM_ADMIN platform-wide, holding those rows against concurrent
     * change until the calling transaction ends.
     *
     * <p>A count rather than a list because the only question asked of it is "would demoting this
     * person leave zero" -- and loading every platform admin to answer it would be a list built to
     * be thrown away. Returning zero is possible and is the state the startup bootstrap exists to
     * leave behind (ADR-027).
     *
     * <p><b>Named for the lock because callers depend on it.</b> "Would this leave zero" is a
     * check-then-act, and without the lock two overlapping demotions each see the other's victim
     * still standing and both proceed. <b>Call it inside a transaction</b> or the lock is released
     * before the delete it is guarding.
     */
    long countPlatformAdminsForUpdate();
}
