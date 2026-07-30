package com.paymesh.identity.application;

import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;

import java.util.Optional;

public interface UserRepository {

    boolean existsByEmail(String normalizedEmail);

    User save(User user);

    Optional<User> findByEmail(String normalizedEmail);

    Optional<User> findByUserId(UserId userId);
}
