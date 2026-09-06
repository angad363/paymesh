package com.paymesh.shared.security;

import com.paymesh.shared.tenant.MerchantId;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Who is calling, and what they are allowed to be.
 *
 * <h2>THE ROLE USED TO BE PARSED AND THROWN AWAY</h2>
 *
 * This record held {@code (userId, Set<MerchantId>)}. The resolver read
 * {@code "<ROLE>:<merchantId>"} off the claim, split it, kept the merchant and <b>discarded the
 * role</b> -- so {@code MERCHANT_USER} could refund exactly as {@code PLATFORM_ADMIN} could, and
 * authorization was "are you scoped to this tenant" and nothing more. The Phase 1 audit recorded it
 * as open item 10; ADR-021 closes it.
 *
 * <h2>PLATFORM ROLES ARE A SEPARATE SET, NOT AN ENTRY IN THE MAP</h2>
 *
 * They used to be found by scanning {@code rolesByMerchant} for PLATFORM_ADMIN at any merchant --
 * see {@link #requirePlatformAdmin()} for what that cost. Since V23 a platform role has no
 * merchant to be keyed by, so it lives in its own field and the map means only what its name says.
 * ADR-027.
 *
 * @param userId the subject of the access token
 * @param rolesByMerchant every merchant the caller holds a role at, and which roles. Possibly
 *     empty -- a token with no parseable scope grants nothing.
 * @param platformRoles roles held across the whole platform rather than at any tenant. Almost
 *     always empty; a non-empty value is the rarest and most powerful thing in a token.
 */
public record AuthenticatedCaller(
    String userId,
    Map<MerchantId, Set<CallerRole>> rolesByMerchant,
    Set<CallerRole> platformRoles
) {

    public AuthenticatedCaller {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated caller must have a user id");
        }

        Map<MerchantId, Set<CallerRole>> copy = new LinkedHashMap<>();

        rolesByMerchant.forEach((merchantId, roles) ->
            copy.put(merchantId, Collections.unmodifiableSet(EnumSet.copyOf(roles))));

        rolesByMerchant = Collections.unmodifiableMap(copy);

        platformRoles = platformRoles.isEmpty()
            ? Set.of()
            : Collections.unmodifiableSet(EnumSet.copyOf(platformRoles));
    }

    /** A caller holding no platform role -- which is nearly every caller. */
    public AuthenticatedCaller(String userId, Map<MerchantId, Set<CallerRole>> rolesByMerchant) {
        this(userId, rolesByMerchant, Set.of());
    }

    /** Every merchant this caller holds any role at. */
    public Set<MerchantId> merchantIds() {
        return rolesByMerchant.keySet();
    }

    public boolean canActFor(MerchantId merchantId) {
        return rolesByMerchant.containsKey(merchantId);
    }

    /**
     * The one merchant this caller acts for.
     * <p>
     * A caller holding roles at several merchants is genuine -- an accountant serving two
     * businesses -- and such a caller must say which one they mean rather than have the platform
     * guess. Nothing in the API yet lets them say, so they are refused rather than served the
     * wrong tenant's data.
     */
    public MerchantId requireSingleMerchant() {
        if (rolesByMerchant.size() != 1) {
            throw new NoMerchantScopeException(
                rolesByMerchant.isEmpty()
                    ? "This token carries no merchant scope"
                    : "This token carries " + rolesByMerchant.size()
                        + " merchant scopes; the request must name which one it means"
            );
        }

        return rolesByMerchant.keySet().iterator().next();
    }

    /**
     * The one merchant this caller acts for, refusing unless they hold {@code required} there.
     *
     * <h2>THE ROLE IS CHECKED AT THE MERCHANT, NOT GLOBALLY</h2>
     *
     * Holding MERCHANT_ADMIN at merchant A must not authorize an admin action at merchant B. Since
     * this resolves to exactly one merchant first, the check is necessarily scoped to that one --
     * a global "does this caller hold admin anywhere" test would be the cross-tenant hole this
     * whole model exists to prevent.
     */
    public MerchantId requireSingleMerchantWith(CallerRole required) {
        MerchantId merchantId = requireSingleMerchant();

        if (!rolesByMerchant.get(merchantId).contains(required)) {
            throw new InsufficientRoleException(required);
        }

        return merchantId;
    }

    /**
     * Refuses unless the caller is platform staff.
     *
     * <h2>PLATFORM_ADMIN IS NOT SCOPED TO A MERCHANT, AND THAT IS WHY IT IS SEPARATE</h2>
     *
     * Suspending a merchant is an act performed ON a tenant by someone outside it. A caller who
     * held PLATFORM_ADMIN "at" a merchant would be that merchant's own staff granting themselves
     * the power to lift their own suspension, which would make suspension advisory.
     *
     * <h2>WHICH IS EXACTLY WHAT THIS METHOD USED TO ACCEPT</h2>
     *
     * It read {@code PLATFORM_ADMIN:<any merchant>} as platform authority -- scanning
     * {@code rolesByMerchant} for the role at ANY tenant -- because there was no other shape a
     * token could carry it in. That was safe only because no endpoint could grant the role, and it
     * stopped being safe the moment one could. Since V23 the role has no merchant at all: it is
     * held here, {@code ck_user_roles_scope} refuses to store the merchant-scoped shape, and
     * {@code User.grantRoleAt} refuses to produce it. Three layers, because the failure mode is a
     * tenant promoting itself. ADR-027.
     */
    public String requirePlatformAdmin() {
        if (!isPlatformAdmin()) {
            throw new InsufficientRoleException(CallerRole.PLATFORM_ADMIN);
        }

        return userId;
    }

    /**
     * Whether this caller is platform staff, without refusing when they are not.
     *
     * <h2>ONE READER, BECAUSE THERE USED TO BE TWO AND THEY DRIFTED</h2>
     *
     * {@code MerchantStatusFilter} asks the same question for the opposite purpose -- to let
     * platform staff PAST the merchant-active gate, since the merchant they are about to activate
     * is by definition not active yet. It had its own copy of the {@code rolesByMerchant} scan, so
     * moving platform roles out of that map fixed {@link #requirePlatformAdmin()} and silently
     * broke the filter: a platform admin who also held a merchant role became unable to activate
     * anything, which is the deadlock ADR-027 exists to remove, reached one layer up.
     * <p>
     * Both go through here now. The same argument {@code AuthenticatedCallers} makes for being the
     * one parser of the claim.
     */
    public boolean isPlatformAdmin() {
        return platformRoles.contains(CallerRole.PLATFORM_ADMIN);
    }
}
