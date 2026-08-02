package com.paymesh.shared.security;

import com.paymesh.shared.tenant.MerchantId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one place that turns a verified access token into an {@link AuthenticatedCaller}.
 * <p>
 * Two readers of the same claim that drift apart is the bug this exists to prevent: the argument
 * resolver and the idempotency filter both need the caller's merchant, and if they ever disagreed
 * about how to parse a scoped role, a request could be idempotency-scoped to one tenant and written
 * under another.
 * <p>
 * Roles travel as {@code "<ROLE>:<merchantId>"}, because one user can hold roles at several
 * merchants and the scope has to stay attached to the role it qualifies. Only the merchant part is
 * read here; which role it is becomes interesting when endpoints differ by permission.
 * <p>
 * A malformed merchant id in a token is ignored rather than fatal. The token was signed by us, so a
 * bad value means our own data drifted, and the safe reading of an unparseable scope is that the
 * caller does not hold it -- which denies access rather than granting something unintended.
 */
public final class AuthenticatedCallers {

    private static final String ROLES_CLAIM = "roles";

    private AuthenticatedCallers() {
    }

    /**
     * The caller behind the current request, or empty when the request is not authenticated by a
     * bearer token. Empty is a real answer, not an error: a servlet filter can run on a route the
     * security chain leaves anonymous.
     */
    public static Optional<AuthenticatedCaller> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        return Optional.of(new AuthenticatedCaller(jwt.getSubject(), rolesByMerchant(jwt)));
    }

    /**
     * Parses {@code "<ROLE>:<merchantId>"} into merchant-scoped roles.
     *
     * <h2>THE ROLE USED TO BE SPLIT OFF AND DROPPED ON THE FLOOR</h2>
     *
     * This method kept only the merchant id, which is why every authenticated caller had identical
     * authority at their tenant. It now keeps both halves. ADR-021.
     *
     * <h2>Unparseable entries grant nothing, and never throw</h2>
     *
     * A malformed or unknown scope is skipped rather than rejected. Throwing would turn one bad
     * entry in a token into a total denial for a caller whose other scopes are fine -- and an
     * attacker who can put a string in a claim could then deny service to anyone. Skipping means
     * the worst a bad entry can do is grant less.
     */
    private static Map<MerchantId, Set<CallerRole>> rolesByMerchant(Jwt jwt) {
        List<String> scopedRoles = jwt.getClaimAsStringList(ROLES_CLAIM);

        if (scopedRoles == null) {
            return Map.of();
        }

        Map<MerchantId, Set<CallerRole>> byMerchant = new LinkedHashMap<>();

        for (String scopedRole : scopedRoles) {
            int separator = scopedRole.indexOf(':');

            if (separator < 1 || separator == scopedRole.length() - 1) {
                continue;
            }

            Optional<CallerRole> role = CallerRole.parse(scopedRole.substring(0, separator));

            if (role.isEmpty()) {
                continue;
            }

            try {
                MerchantId merchantId = MerchantId.from(scopedRole.substring(separator + 1));

                byMerchant.computeIfAbsent(merchantId, id -> EnumSet.noneOf(CallerRole.class))
                    .add(role.get());
            } catch (IllegalArgumentException ignored) {
                // Unparseable merchant grants nothing. See the method comment.
            }
        }

        return byMerchant;
    }
}
