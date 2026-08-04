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
import java.util.Optional;

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

    /**
     * Promotes a user to platform staff.
     *
     * <h2>THE ROLE THAT COULD NOT BE GRANTED, AND WHAT THAT COST</h2>
     *
     * {@code user_roles.merchant_id} was NOT NULL, so no endpoint could produce a platform-wide
     * grant. But merchant activation is PLATFORM_ADMIN-only and nothing merchant-scoped works
     * until a merchant is ACTIVE -- so onboarding was only walkable by minting a token with the
     * published dev signing key. V23 gives the row somewhere to live; this is what writes it.
     * ADR-027.
     *
     * <h2>Every live session ends, and that is not the usual courtesy</h2>
     *
     * Their existing access token does not carry the new role and will not until it is reissued,
     * so without this the promotion appears not to have worked for up to fifteen minutes. Ending
     * the sessions forces a refresh, and a refresh reissues the claim. The same mechanism as
     * revocation, used for the opposite reason.
     */
    public User grantPlatformAdmin(UserId userId, String operatorId) {
        return transactions.execute(status -> {
            User promoted = users.save(require(userId).grantPlatformRole(Role.PLATFORM_ADMIN, now()));

            endEverySession(userId, SecurityEventType.PLATFORM_ADMIN_GRANTED);

            log.warn("Granted PLATFORM_ADMIN userId={} operator={}", userId.value(), operatorId);

            return promoted;
        });
    }

    /**
     * Demotes a user out of platform staff.
     *
     * <h2>THE LAST PLATFORM ADMIN CANNOT REMOVE THEMSELVES</h2>
     *
     * Not paternalism, and the same argument as {@link #revokeAccessAt}: PLATFORM_ADMIN is the
     * only role that can grant PLATFORM_ADMIN, so a platform with none has no way back except the
     * startup bootstrap or a hand-written UPDATE. Refused rather than made recoverable.
     * <p>
     * Checked as "would this leave zero", not "is the caller the target" -- an admin demoting a
     * COLLEAGUE while they are themselves the only other one is the same outcome reached from the
     * other side, and a self-check alone would miss it.
     */
    public User revokePlatformAdmin(UserId userId, String operatorId) {
        return transactions.execute(status -> {
            User target = require(userId);

            if (target.hasPlatformRole(Role.PLATFORM_ADMIN)
                && users.countPlatformAdmins() <= 1) {
                throw new LastPlatformAdminException(userId);
            }

            User demoted = users.save(target.revokePlatformRole(Role.PLATFORM_ADMIN, now()));

            endEverySession(userId, SecurityEventType.PLATFORM_ADMIN_REVOKED);

            log.warn("Revoked PLATFORM_ADMIN userId={} operator={}", userId.value(), operatorId);

            return demoted;
        });
    }

    /**
     * Mints the FIRST platform admin at startup, from an operator-supplied email.
     *
     * <h2>THE CHICKEN AND EGG THAT MAKES THIS NECESSARY</h2>
     *
     * {@link #grantPlatformAdmin} requires a caller who already holds PLATFORM_ADMIN, so on a
     * fresh database no API call can ever produce the first one. The alternatives were a seeded
     * user in a migration -- which means a password hash committed to git, in a repository whose
     * {@code DevelopmentSecretGuard} exists specifically to refuse committed secrets -- or a
     * hand-written UPDATE in every runbook. This is the third option and it handles no secret at
     * all: the human registers through the ordinary endpoint, and the operator names them.
     *
     * <h2>It promotes, it never creates</h2>
     *
     * An unknown email logs a warning and does nothing. Creating an account here would mean
     * inventing a password, and a platform admin account with a password nobody chose is a
     * credential waiting to be guessed.
     *
     * <h2>It runs only while there are none</h2>
     *
     * Once any platform admin exists, this returns without touching anything -- so leaving the
     * property set in a deployment does not silently re-promote somebody who was deliberately
     * demoted. Idempotent on every boot after the first.
     *
     * @return the promoted user, or empty when nothing was done
     */
    public Optional<User> bootstrapPlatformAdmin(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        if (users.countPlatformAdmins() > 0) {
            log.info("Platform admin bootstrap skipped: the platform already has one");

            return Optional.empty();
        }

        Optional<User> candidate = users.findByEmail(User.normalizeEmail(email));

        if (candidate.isEmpty()) {
            log.warn(
                "Platform admin bootstrap found no user for the configured email. "
                    + "Register that account, then restart. No admin was created."
            );

            return Optional.empty();
        }

        User promoted = users.save(candidate.get().grantPlatformRole(Role.PLATFORM_ADMIN, now()));

        securityEvents.save(SecurityEvent.record(
            SecurityEventType.PLATFORM_ADMIN_GRANTED, promoted.userId().value(), null, now()
        ));

        log.warn(
            "Bootstrapped the first PLATFORM_ADMIN userId={} from configuration",
            promoted.userId().value()
        );

        return Optional.of(promoted);
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
     *
     * <h2>THERE IS NO CONSENT STEP, AND THAT IS A KNOWN LIMITATION</h2>
     *
     * Any merchant admin may grant a role to any user id, without that user agreeing to it. The
     * proper shape is an invitation the user accepts, and this is not that. Two things keep the
     * exposure small rather than absent, and neither is a substitute:
     * <ul>
     *   <li>User ids are {@code usr_} + UUID, so a caller cannot enumerate targets -- they have to
     *       already know an id.</li>
     *   <li>The granted role reaches into the GRANTING merchant's data, not the user's. There is
     *       nothing of the user's to steal by adding them.</li>
     * </ul>
     * What it does leak is existence: a grant to a real id answers 200 and to an unknown one 404,
     * where {@link #revokeAccessAt} deliberately answers 404 for both. Recorded in ADR-024 and in
     * project-status rather than left for the next audit.
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
