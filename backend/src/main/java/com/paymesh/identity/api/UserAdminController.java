package com.paymesh.identity.api;

import com.paymesh.identity.application.ManageUserAccessService;
import com.paymesh.identity.domain.Role;
import com.paymesh.identity.domain.UserId;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.security.CallerRole;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Disabling people, at the two scopes that mean different things (ADR-024).
 *
 * <h2>WHY THERE ARE TWO KINDS OF ROUTE HERE</h2>
 *
 * ADR-023 left "who may disable a user" open, and the honest answer turned out to be that it is two
 * questions:
 *
 * <ul>
 *   <li><b>{@code DELETE /api/v1/users/{id}/merchant-access}</b> -- a MERCHANT_ADMIN removing
 *       somebody's roles at their own merchant. The departed-employee case. The account survives,
 *       because that person may hold a role at another merchant and merchant A must not be able to
 *       lock them out of merchant B.</li>
 *   <li><b>{@code POST /api/v1/users/{id}/suspend|reactivate|close}</b> -- PLATFORM_ADMIN barring
 *       the human from PayMesh entirely. It reaches across every tenant, so it is not a tenant's
 *       decision to make.</li>
 * </ul>
 *
 * <h2>The list is merchant-scoped and the platform routes are not</h2>
 *
 * {@code GET /api/v1/users} shows only the callers's own merchant's people -- an admin needs to see
 * their staff before revoking one, and must not be able to enumerate anybody else's. The platform
 * routes take a user id directly, because platform staff act on people rather than on tenants.
 */
@RestController
@RequestMapping("api/v1/users")
public final class UserAdminController {

    private final ManageUserAccessService manageUserAccess;

    public UserAdminController(ManageUserAccessService manageUserAccess) {
        this.manageUserAccess = manageUserAccess;
    }

    // --- merchant scope -------------------------------------------------------------------------

    /** The people who can act for the caller's merchant. */
    @GetMapping
    List<UserAdminResponse> list(AuthenticatedCaller caller) {
        MerchantId merchantId = caller.requireSingleMerchantWith(CallerRole.MERCHANT_ADMIN);

        return manageUserAccess.listAtMerchant(merchantId).stream()
            .map(UserAdminResponse::from)
            .toList();
    }

    /**
     * Grants a role at the caller's merchant, so revocation is reversible.
     * <p>
     * The user must already exist. Creating an account is registration, which has a password in it
     * and belongs on the auth routes.
     */
    @PostMapping("/{userId}/merchant-access")
    UserAdminResponse grantMerchantAccess(
        @PathVariable String userId,
        @Valid @RequestBody GrantMerchantAccessRequest request,
        AuthenticatedCaller caller
    ) {
        MerchantId merchantId = caller.requireSingleMerchantWith(CallerRole.MERCHANT_ADMIN);

        return UserAdminResponse.from(manageUserAccess.grantAccessAt(
            merchantId, UserId.from(userId), Role.valueOf(request.role()), caller.userId()
        ));
    }

    /**
     * Removes this user's roles at the caller's merchant. THE DEPARTED-EMPLOYEE CASE.
     * <p>
     * A user who holds no role there answers 404, the same as a user who does not exist -- a
     * merchant admin who could tell the two apart could enumerate every user id on the platform.
     */
    @DeleteMapping("/{userId}/merchant-access")
    UserAdminResponse revokeMerchantAccess(
        @PathVariable String userId,
        AuthenticatedCaller caller
    ) {
        MerchantId merchantId = caller.requireSingleMerchantWith(CallerRole.MERCHANT_ADMIN);

        return UserAdminResponse.from(manageUserAccess.revokeAccessAt(
            merchantId, UserId.from(userId), caller.userId()
        ));
    }

    // --- platform scope -------------------------------------------------------------------------

    /**
     * Bars the human from PayMesh, and ends every live session at once.
     * <p>
     * An access token already issued still works for its remaining lifetime -- at most fifteen
     * minutes, because nothing checks a denylist (open item 11). Suspension is therefore "within a
     * quarter hour" rather than instant, and that is unchanged by this.
     */
    @PostMapping("/{userId}/suspend")
    UserAdminResponse suspend(@PathVariable String userId, AuthenticatedCaller caller) {
        return UserAdminResponse.from(manageUserAccess.suspend(
            UserId.from(userId), caller.requirePlatformAdmin()
        ));
    }

    @PostMapping("/{userId}/reactivate")
    UserAdminResponse reactivate(@PathVariable String userId, AuthenticatedCaller caller) {
        return UserAdminResponse.from(manageUserAccess.reactivate(
            UserId.from(userId), caller.requirePlatformAdmin()
        ));
    }

    /** Terminal, like a merchant closure and for the same reason. */
    @PostMapping("/{userId}/close")
    UserAdminResponse close(@PathVariable String userId, AuthenticatedCaller caller) {
        return UserAdminResponse.from(manageUserAccess.close(
            UserId.from(userId), caller.requirePlatformAdmin()
        ));
    }

    /**
     * Promotes a user to platform staff. THE WIDEST GRANT THIS API MAKES.
     *
     * <h2>PLATFORM_ADMIN ONLY, AND DELIBERATELY NOT ON THE MERCHANT-ACCESS ROUTE</h2>
     *
     * The merchant-access route above resolves the caller's own merchant and grants a role there.
     * Routing platform promotion through it -- {@code role: "PLATFORM_ADMIN"} in that body --
     * would mean any merchant admin could promote themselves out of their tenant, which is the
     * escalation ADR-027 exists to close. It has its own path, its own authorization, and
     * {@code User.grantRoleAt} plus {@code ck_user_roles_scope} refuse the other route
     * independently.
     *
     * <h2>Where the FIRST one comes from</h2>
     *
     * Not from here -- an endpoint only a platform admin may call cannot mint the first platform
     * admin. {@code IdentityConfiguration.platformAdminBootstrap} does, at startup, by calling
     * {@code ManageUserAccessService.bootstrapPlatformAdmin} with the email in
     * {@code paymesh.security.bootstrap-platform-admin-email}.
     */
    @PostMapping("/{userId}/platform-admin")
    UserAdminResponse grantPlatformAdmin(@PathVariable String userId, AuthenticatedCaller caller) {
        return UserAdminResponse.from(manageUserAccess.grantPlatformAdmin(
            UserId.from(userId), caller.requirePlatformAdmin()
        ));
    }

    /**
     * Demotes a user out of platform staff.
     * <p>
     * Refused when it would leave the platform with none -- checked as a count rather than as
     * "is this me", because demoting the only OTHER admin reaches the same dead end from the
     * other side.
     */
    @DeleteMapping("/{userId}/platform-admin")
    UserAdminResponse revokePlatformAdmin(@PathVariable String userId, AuthenticatedCaller caller) {
        return UserAdminResponse.from(manageUserAccess.revokePlatformAdmin(
            UserId.from(userId), caller.requirePlatformAdmin()
        ));
    }
}
