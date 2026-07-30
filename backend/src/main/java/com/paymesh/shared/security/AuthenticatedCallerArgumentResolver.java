package com.paymesh.shared.security;

import com.paymesh.shared.tenant.MerchantId;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds an {@link AuthenticatedCaller} from the verified access token so controllers can declare
 * it as a parameter and never touch claims.
 * <p>
 * Roles travel as {@code "<ROLE>:<merchantId>"}, because one user can hold roles at several
 * merchants and the scope has to stay attached to the role it qualifies. Only the merchant part is
 * needed here; which role it is becomes interesting when endpoints differ by permission.
 * <p>
 * A malformed merchant id in a token is ignored rather than fatal. The token was signed by us, so a
 * bad value means our own data drifted, and the safe reading of an unparseable scope is that the
 * caller does not hold it -- which denies access rather than granting something unintended.
 */
public final class AuthenticatedCallerArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String ROLES_CLAIM = "roles";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedCaller.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer modelAndViewContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory
    ) {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
            ? null
            : SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        if (!(principal instanceof Jwt jwt)) {
            // The filter chain requires authentication on every route that can ask for this type,
            // so reaching here means the security configuration and the controllers disagree.
            throw new IllegalStateException(
                "AuthenticatedCaller requested on a route that is not authenticated"
            );
        }

        return new AuthenticatedCaller(jwt.getSubject(), merchantIds(jwt));
    }

    private static Set<MerchantId> merchantIds(Jwt jwt) {
        List<String> scopedRoles = jwt.getClaimAsStringList(ROLES_CLAIM);

        if (scopedRoles == null) {
            return Set.of();
        }

        Set<MerchantId> merchantIds = new LinkedHashSet<>();

        for (String scopedRole : scopedRoles) {
            int separator = scopedRole.indexOf(':');

            if (separator < 0 || separator == scopedRole.length() - 1) {
                continue;
            }

            try {
                merchantIds.add(MerchantId.from(scopedRole.substring(separator + 1)));
            } catch (IllegalArgumentException ignored) {
                // Unparseable scope grants nothing. See the class comment.
            }
        }

        return merchantIds;
    }
}
