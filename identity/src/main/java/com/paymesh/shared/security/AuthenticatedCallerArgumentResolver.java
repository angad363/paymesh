package com.paymesh.shared.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Builds an {@link AuthenticatedCaller} from the verified access token so controllers can declare
 * it as a parameter and never touch claims.
 * <p>
 * The claim parsing itself lives in {@link AuthenticatedCallers}, shared with the idempotency
 * filter -- both need the caller's merchant and neither may have its own idea of how to find it.
 */
public final class AuthenticatedCallerArgumentResolver implements HandlerMethodArgumentResolver {

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
        return AuthenticatedCallers.current().orElseThrow(() ->
            // The filter chain requires authentication on every route that can ask for this type,
            // so reaching here means the security configuration and the controllers disagree.
            new IllegalStateException(
                "AuthenticatedCaller requested on a route that is not authenticated"
            )
        );
    }
}
