package com.paymesh.identity.application;

import com.paymesh.identity.domain.UserId;

/**
 * Refusing to demote the only remaining platform admin.
 *
 * <h2>WHY THIS IS A REFUSAL AND NOT A WARNING</h2>
 *
 * PLATFORM_ADMIN is the only role that can grant PLATFORM_ADMIN. A platform with none has exactly
 * two routes back: restart with the bootstrap property set, or a hand-written UPDATE against
 * {@code user_roles}. Both are operator interventions, and an API call whose undo is an operator
 * intervention is one nobody makes confidently.
 * <p>
 * The same argument {@code CannotRevokeOwnAccessException} makes for a merchant's last admin, one
 * scope up and with a wider blast radius: a platform with no admin cannot activate any merchant,
 * so every merchant registered from that moment on is stuck in PENDING_VERIFICATION.
 */
public final class LastPlatformAdminException extends RuntimeException {

    public LastPlatformAdminException(UserId userId) {
        super(
            "User " + userId.value() + " is the only platform admin. "
                + "Grant PLATFORM_ADMIN to somebody else before revoking this one."
        );
    }
}
