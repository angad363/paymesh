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
 * @param userId the subject of the access token
 * @param rolesByMerchant every merchant the caller holds a role at, and which roles. Possibly
 *     empty -- a token with no parseable scope grants nothing.
 */
public record AuthenticatedCaller(String userId, Map<MerchantId, Set<CallerRole>> rolesByMerchant) {

    public AuthenticatedCaller {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated caller must have a user id");
        }

        Map<MerchantId, Set<CallerRole>> copy = new LinkedHashMap<>();

        rolesByMerchant.forEach((merchantId, roles) ->
            copy.put(merchantId, Collections.unmodifiableSet(EnumSet.copyOf(roles))));

        rolesByMerchant = Collections.unmodifiableMap(copy);
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
     * the power to lift their own suspension, which would make suspension advisory. The claim is
     * therefore read as {@code PLATFORM_ADMIN:<any merchant>} but authority is platform-wide, and
     * nothing in the ordinary registration path ever assigns it.
     */
    public String requirePlatformAdmin() {
        boolean isPlatformAdmin = rolesByMerchant.values().stream()
            .anyMatch(roles -> roles.contains(CallerRole.PLATFORM_ADMIN));

        if (!isPlatformAdmin) {
            throw new InsufficientRoleException(CallerRole.PLATFORM_ADMIN);
        }

        return userId;
    }
}
