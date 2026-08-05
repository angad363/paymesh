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
            // uq_users_email USED TO BE the only constraint reachable from here, so every
            // violation could safely be read as a taken email. V23 added ck_user_roles_scope and
            // two partial unique indexes on user_roles, and reporting one of those as "email
            // already registered" would be a 409 naming the wrong field -- a lie in the one
            // direction an integrator cannot debug. Anything else propagates as a 500, which is
            // honest: the domain refuses those shapes before they reach here, so seeing one means
            // a bug rather than a race.
            if (!namesEmailConstraint(exception)) {
                throw exception;
            }

            // The email was taken between the service's existsByEmail check and this insert. The
            // loser of that race must still get a 409, not a 500.
            throw new UserEmailAlreadyExistsException(user.email());
        }
    }

    private static boolean namesEmailConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();

        return message != null && message.contains("uq_users_email");
    }

    @Override
    public Optional<User> findByEmail(String normalizedEmail) {
        return users.findByEmail(normalizedEmail).map(UserJpaMapper::toDomain);
    }

    @Override
    public java.util.List<User> findByMerchant(com.paymesh.shared.tenant.MerchantId merchantId) {
        return users.findByMerchant(merchantId.value())
            .stream()
            .map(UserJpaMapper::toDomain)
            .toList();
    }

    @Override
    public Optional<User> findByUserId(UserId userId) {
        return users.findById(userId.value()).map(UserJpaMapper::toDomain);
    }

    @Override
    public long countPlatformAdminsForUpdate() {
        return users.countPlatformAdminsForUpdate();
    }
}
