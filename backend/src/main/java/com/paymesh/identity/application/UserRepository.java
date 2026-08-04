package com.paymesh.identity.application;

import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;

import java.util.Optional;

public interface UserRepository {

    boolean existsByEmail(String normalizedEmail);

    User save(User user);

    Optional<User> findByEmail(String normalizedEmail);

    Optional<User> findByUserId(UserId userId);

    /** Everyone holding any role at this merchant. What an admin needs before revoking anybody. */
    java.util.List<User> findByMerchant(com.paymesh.shared.tenant.MerchantId merchantId);

    /**
     * How many users hold PLATFORM_ADMIN platform-wide.
     *
     * <p>A count rather than a list because the only question asked of it is "would demoting this
     * person leave zero" -- and loading every platform admin to answer it would be a list built to
     * be thrown away. Returning zero is possible and is the state the startup bootstrap exists to
     * leave behind (ADR-027).
     */
    long countPlatformAdmins();
}
