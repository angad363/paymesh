package com.paymesh.identity.application;

import com.paymesh.identity.domain.Role;
import com.paymesh.identity.domain.RoleAssignment;
import com.paymesh.identity.domain.SecurityEvent;
import com.paymesh.identity.domain.SecurityEventType;
import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Creates a user account.
 *
 * <p>Deliberately minimal: it exists so login has something to authenticate
 * against. The real onboarding path is an invitation from the Merchant Service
 * (merchant.user.invited, SDD 8.5), which is not built yet.
 */
public final class RegisterUserService {

    /**
     * BCrypt hashes at most the first 72 bytes of the password; anything beyond
     * that is silently ignored, which would make two different long passwords
     * interchangeable. Reject rather than truncate.
     */
    private static final int MAX_PASSWORD_LENGTH = 72;
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final SecurityEventRepository securityEventRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public RegisterUserService(
        UserRepository userRepository,
        SecurityEventRepository securityEventRepository,
        PasswordHasher passwordHasher,
        Clock clock
    ) {
        this.userRepository = userRepository;
        this.securityEventRepository = securityEventRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    public User register(RegisterUserCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Register User Command cannot be null");
        }

        Instant registeredAt = Instant.now(clock);

        User user = User.register(
            UserId.generate(),
            command.email(),
            passwordHasher.hash(requirePassword(command.password())),
            rolesFor(command.merchantId()),
            registeredAt
        );

        // A check, not a lock: two concurrent registrations can both pass it. The
        // uq_users_email constraint is the real guard and the adapter translates it
        // into this same exception, so the loser of the race still sees a 409.
        if (userRepository.existsByEmail(user.email())) {
            throw new UserEmailAlreadyExistsException(user.email());
        }

        User savedUser = userRepository.save(user);

        securityEventRepository.save(
            SecurityEvent.record(
                SecurityEventType.USER_REGISTERED,
                savedUser.userId().value(),
                command.ipAddress(),
                registeredAt
            )
        );

        return savedUser;
    }

    /**
     * Length is checked here rather than on the request record because the domain
     * never sees the plaintext -- by the time User.register runs, the password is
     * already a 60-character hash and its original length is unrecoverable.
     */
    private static String requirePassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password cannot be null");
        }

        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                "Password must be at least " + MIN_PASSWORD_LENGTH + " characters"
            );
        }

        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                "Password cannot be longer than " + MAX_PASSWORD_LENGTH + " characters"
            );
        }

        return password;
    }

    private static List<RoleAssignment> rolesFor(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return List.of();
        }

        return List.of(new RoleAssignment(Role.MERCHANT_ADMIN, merchantId));
    }
}
