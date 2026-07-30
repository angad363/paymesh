package com.paymesh.identity.infrastructure.persistence.jpa;

import com.paymesh.identity.application.UserEmailAlreadyExistsException;
import com.paymesh.identity.application.UserRepository;
import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

/**
 * PostgreSQL-backed implementation of the application's UserRepository port.
 * Everything JPA stays on this side of the interface; the services see only
 * domain types.
 */
public final class JpaUserRepository implements UserRepository {

    private final SpringDataUserRepository users;

    public JpaUserRepository(SpringDataUserRepository users) {
        this.users = users;
    }

    @Override
    public boolean existsByEmail(String normalizedEmail) {
        return users.existsByEmail(normalizedEmail);
    }

    @Override
    public User save(User user) {
        try {
            UserJpaEntity saved = users.saveAndFlush(UserJpaMapper.toEntity(user));
            return UserJpaMapper.toDomain(saved);
        } catch (DataIntegrityViolationException exception) {
            // uq_users_email is the only unique constraint on the table besides the
            // primary key (which is a fresh UUID), so a violation here means the
            // email was taken between the service's existsByEmail check and this
            // insert. The loser of that race must still get a 409, not a 500.
            throw new UserEmailAlreadyExistsException(user.email());
        }
    }

    @Override
    public Optional<User> findByEmail(String normalizedEmail) {
        return users.findByEmail(normalizedEmail).map(UserJpaMapper::toDomain);
    }

    @Override
    public Optional<User> findByUserId(UserId userId) {
        return users.findById(userId.value()).map(UserJpaMapper::toDomain);
    }
}
