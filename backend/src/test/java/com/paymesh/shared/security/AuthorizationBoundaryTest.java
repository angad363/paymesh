package com.paymesh.shared.security;

import com.paymesh.identity.domain.Role;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authorization stopped being "are you scoped to this tenant".
 * <p>
 * {@code AuthenticatedCaller} used to hold {@code (userId, Set<MerchantId>)}: the resolver split
 * {@code "<ROLE>:<merchantId>"}, kept the merchant and <b>discarded the role</b>, so MERCHANT_USER
 * could refund exactly as PLATFORM_ADMIN could. Open item 10; ADR-021.
 */
class AuthorizationBoundaryTest {

    private static final MerchantId MERCHANT = MerchantId.generate();
    private static final MerchantId OTHER = MerchantId.generate();
    private static final String USER = "usr_00000000-0000-4000-8000-000000000001";

    /**
     * THE TWO ENUMS MUST NOT DRIFT.
     * <p>
     * {@code CallerRole} is deliberately not {@code identity.domain.Role} -- {@code shared} may not
     * import a capability, so the token claim is a PUBLISHED contract rather than a shared type
     * (the same trade the simulator makes for the callback contract). The cost of publishing is
     * that the two can diverge, and this is the notification a shared type would have suppressed.
     */
    @Test
    void publishesExactlyTheRolesIdentityMints() {
        assertThat(Arrays.stream(CallerRole.values()).map(Enum::name).toList())
            .containsExactlyInAnyOrderElementsOf(
                Arrays.stream(Role.values()).map(Enum::name).toList()
            );
    }

    @Test
    void grantsTheRoleItWasGiven() {
        AuthenticatedCaller caller = callerWith(CallerRole.MERCHANT_ADMIN);

        assertThat(caller.requireSingleMerchantWith(CallerRole.MERCHANT_ADMIN)).isEqualTo(MERCHANT);
    }

    /** The whole point: an operational user is no longer an administrator. */
    @Test
    void refusesAnAdminActionToAMerchantUser() {
        AuthenticatedCaller caller = callerWith(CallerRole.MERCHANT_USER);

        assertThat(caller.requireSingleMerchant())
            .as("still resolves to the tenant")
            .isEqualTo(MERCHANT);

        assertThatThrownBy(() -> caller.requireSingleMerchantWith(CallerRole.MERCHANT_ADMIN))
            .isInstanceOf(InsufficientRoleException.class);
    }

    @Test
    void refusesPlatformActionsToMerchantStaff() {
        assertThatThrownBy(() -> callerWith(CallerRole.MERCHANT_ADMIN).requirePlatformAdmin())
            .isInstanceOf(InsufficientRoleException.class)
            .hasMessageContaining("PLATFORM_ADMIN");
    }

    @Test
    void allowsPlatformActionsToPlatformStaff() {
        assertThat(callerWith(CallerRole.PLATFORM_ADMIN).requirePlatformAdmin()).isEqualTo(USER);
    }

    /**
     * ADMIN AT ONE MERCHANT IS NOT ADMIN AT ANOTHER. The check is necessarily scoped, because it
     * runs after resolving to exactly one merchant -- a global "holds admin anywhere" test would be
     * the cross-tenant hole the whole model exists to prevent.
     */
    @Test
    void doesNotCarryARoleAcrossTenants() {
        AuthenticatedCaller caller = new AuthenticatedCaller(USER, Map.of(
            MERCHANT, EnumSet.of(CallerRole.MERCHANT_ADMIN),
            OTHER, EnumSet.of(CallerRole.MERCHANT_USER)
        ));

        assertThat(caller.canActFor(MERCHANT)).isTrue();
        assertThat(caller.canActFor(OTHER)).isTrue();

        // Two scopes cannot resolve to one merchant, so the request is refused rather than served
        // the wrong tenant's data.
        assertThatThrownBy(caller::requireSingleMerchant)
            .isInstanceOf(NoMerchantScopeException.class);
    }

    @Test
    void grantsNothingWithNoScope() {
        AuthenticatedCaller caller = new AuthenticatedCaller(USER, Map.of());

        assertThat(caller.merchantIds()).isEmpty();
        assertThatThrownBy(caller::requireSingleMerchant)
            .isInstanceOf(NoMerchantScopeException.class);
        assertThatThrownBy(caller::requirePlatformAdmin)
            .isInstanceOf(InsufficientRoleException.class);
    }

    /** An unknown role in a claim grants nothing rather than everything. */
    @Test
    void parsesOnlyKnownRoles() {
        assertThat(CallerRole.parse("MERCHANT_ADMIN")).contains(CallerRole.MERCHANT_ADMIN);
        assertThat(CallerRole.parse("merchant_user")).contains(CallerRole.MERCHANT_USER);
        assertThat(CallerRole.parse("SUPER_ADMIN")).isEmpty();
        assertThat(CallerRole.parse(null)).isEmpty();
        assertThat(CallerRole.parse("")).isEmpty();
    }

    private static AuthenticatedCaller callerWith(CallerRole role) {
        return new AuthenticatedCaller(USER, Map.<MerchantId, Set<CallerRole>>of(
            MERCHANT, EnumSet.of(role)
        ));
    }
}
