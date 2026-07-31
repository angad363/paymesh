package com.paymesh.identity.infrastructure.persistence.jpa;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.identity.application.UserEmailAlreadyExistsException;
import com.paymesh.identity.application.UserRepository;
import com.paymesh.identity.domain.Role;
import com.paymesh.identity.domain.RoleAssignment;
import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@Transactional
class JpaUserRepositoryTest {

    private static final String BCRYPT_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final String MERCHANT_ID = "mrc_550e8400-e29b-41d4-a716-446655440000";

    @Autowired
    private UserRepository repository;

    /** Roles live in a second table; the round trip is what proves they survive it. */
    @Test
    void savesAndReadsBackAUserWithItsScopedRoles() {
        User user = register(
            "roundtrip@paymesh.test",
            List.of(new RoleAssignment(Role.MERCHANT_ADMIN, MERCHANT_ID))
        );

        repository.save(user);

        User byId = repository.findByUserId(user.userId()).orElseThrow();
        assertThat(byId).usingRecursiveComparison().isEqualTo(user);

        User byEmail = repository.findByEmail("roundtrip@paymesh.test").orElseThrow();
        assertThat(byEmail.roles())
            .containsExactly(new RoleAssignment(Role.MERCHANT_ADMIN, MERCHANT_ID));

        assertThat(repository.existsByEmail("roundtrip@paymesh.test")).isTrue();
    }

    @Test
    void returnsEmptyWhenUserIdIsUnknown() {
        assertThat(repository.findByUserId(UserId.generate())).isEmpty();
    }

    /**
     * Simulates the registration race: the service's existsByEmail check passes,
     * then someone else inserts the same email before the write lands. The unique
     * constraint must surface as the business exception (409), not as a raw
     * DataIntegrityViolationException (500).
     */
    @Test
    void translatesTheUniqueEmailConstraintIntoABusinessException() {
        repository.save(register("race@paymesh.test", List.of()));

        assertThatThrownBy(() -> repository.save(register("race@paymesh.test", List.of())))
            .isInstanceOf(UserEmailAlreadyExistsException.class)
            .hasMessage("A user already exists with email race@paymesh.test");
    }

    private static User register(String email, List<RoleAssignment> roles) {
        return User.register(UserId.generate(), email, BCRYPT_HASH, roles, Instant.now());
    }
}
