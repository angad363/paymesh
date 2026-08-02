package com.paymesh.identity.application;

import com.paymesh.identity.domain.Role;
import com.paymesh.identity.domain.SecurityEvent;
import com.paymesh.identity.domain.SecurityEventType;
import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Disabling people, at the two different scopes that mean different things.
 *
 * <h2>THE QUESTION ADR-023 DEFERRED, ANSWERED</h2>
 *
 * "Who may disable a user -- platform staff, or a merchant admin over their own staff?" turned out
 * to be the wrong question, because it assumed one operation. There are two:
 *
 * <ul>
 *   <li><b>Revoking access at one merchant</b> removes that user's roles there. It is the departed
 *       employee case, it is the merchant's own business, and it is what a merchant admin may do.
 *       The account survives -- the same person may legitimately hold a role at another merchant,
 *       which {@code AuthenticatedCaller} already accounts for.</li>
 *   <li><b>Suspending the account</b> bars the human from PayMesh entirely. It is platform staff's
 *       call, because it reaches across every tenant they belong to.</li>
 * </ul>
 *
 * Conflating them would have let merchant A lock somebody out of merchant B. ADR-024.
 *
 * <h2>Suspension revokes every live session immediately</h2>
 *
 * The refresh path already re-reads the user and kills the family when it cannot authenticate, so
 * a suspension would bite at the next refresh anyway. Revoking here as well means it bites at once,
 * and means the bar does not depend on that one check still being there. Defence in depth on the
 * path where the failure is somebody keeping access they should have lost.
 * <p>
 * <b>An access token already issued still works for its remaining lifetime</b> -- at most fifteen
 * minutes, because nothing checks a denylist (open item 11). That is unchanged and is the reason
 * suspension is "within a quarter hour" rather than instant.
 */
public final class ManageUserAccessService {

    private static final Logger log = LoggerFactory.getLogger(ManageUserAccessService.class);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final SecurityEventRepository securityEvents;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ManageUserAccessService(
        UserRepository users,
        RefreshTokenRepository refreshTokens,
        SecurityEventRepository securityEvents,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.securityEvents = securityEvents;
        this.transactions = transactions;
        this.clock = clock;
    }

    // --- platform scope -------------------------------------------------------------------------

    /** Bars the human from PayMesh entirely, and ends every live session. Reversible. */
    public User suspend(UserId userId, String operatorId) {
        return transactions.execute(status -> {
            User suspended = users.save(require(userId).suspend(now()));

            endEverySession(userId, SecurityEventType.USER_SUSPENDED);

            log.warn("User suspended userId={} operator={}", userId.value(), operatorId);

            return suspended;
        });
    }

    public User reactivate(UserId userId, String operatorId) {
        User reactivated = users.save(require(userId).reactivate(now()));

        securityEvents.save(SecurityEvent.record(
            SecurityEventType.USER_REACTIVATED, userId.value(), null, now()
        ));

        log.warn("User reactivated userId={} operator={}", userId.value(), operatorId);

        return reactivated;
    }

    /** Terminal, like a merchant closure and for the same reason. */
    public User close(UserId userId, String operatorId) {
        return transactions.execute(status -> {
            User closed = users.save(require(userId).close(now()));

            endEverySession(userId, SecurityEventType.USER_CLOSED);

            log.warn("User closed userId={} operator={}", userId.value(), operatorId);

            return closed;
        });
    }

    // --- merchant scope -------------------------------------------------------------------------

    /**
     * Removes a user's roles at ONE merchant. The departed-employee case.
     *
     * <h2>A MERCHANT ADMIN MAY NOT REVOKE THEIR OWN ACCESS</h2>
     *
     * Not paternalism: they are the only role that can grant it back, so a merchant with one admin
     * who revoked themselves would have no way to administer their own account and would need
     * PayMesh to intervene. Refused rather than made recoverable.
     *
     * @throws com.paymesh.identity.domain.UserHoldsNoRoleAtMerchantException when the user holds no
     *     role there -- the same answer as no such user, so a merchant admin cannot enumerate user
     *     ids by watching which ones answer differently
     */
    public User revokeAccessAt(MerchantId merchantId, UserId userId, String actorId) {
        if (userId.value().equals(actorId)) {
            throw new CannotRevokeOwnAccessException(userId);
        }

        return transactions.execute(status -> {
            User revoked = users.save(require(userId).revokeRolesAt(merchantId.value(), now()));

            // Their token still names the merchant until it expires, so the sessions go too --
            // otherwise "revoked" means "in up to fifteen minutes" with nothing saying so.
            endEverySession(userId, SecurityEventType.MERCHANT_ACCESS_REVOKED);

            log.warn(
                "Revoked user access userId={} merchantId={} actor={}",
                userId.value(), merchantId.value(), actorId
            );

            return revoked;
        });
    }

    /**
     * Grants a role at the caller's merchant, so revocation is reversible.
     * <p>
     * The user must already exist -- this adds a role to a person, it does not create one. Creating
     * an account is registration, which is a different thing with a password in it.
     */
    public User grantAccessAt(MerchantId merchantId, UserId userId, Role role, String actorId) {
        User granted = users.save(require(userId).grantRoleAt(role, merchantId.value(), now()));

        log.warn(
            "Granted user access userId={} merchantId={} role={} actor={}",
            userId.value(), merchantId.value(), role, actorId
        );

        return granted;
    }

    /** The people who can act for this merchant. What an admin needs before revoking anybody. */
    public List<User> listAtMerchant(MerchantId merchantId) {
        return users.findByMerchant(merchantId);
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * Ends every live session and records WHY, under its own event type.
     * <p>
     * The refresh path already kills a family when the user cannot authenticate, so a suspension
     * would bite at the next refresh anyway. Doing it here as well means it bites at once, and
     * means the bar does not depend on that one check still being there.
     */
    private void endEverySession(UserId userId, SecurityEventType reason) {
        refreshTokens.revokeAllForUser(userId, now());

        securityEvents.save(SecurityEvent.record(reason, userId.value(), null, now()));
    }

    private User require(UserId userId) {
        return users.findByUserId(userId).orElseThrow(() -> new UserNotFoundException(userId.value()));
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
