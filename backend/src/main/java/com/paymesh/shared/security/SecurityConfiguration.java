package com.paymesh.shared.security;

import com.paymesh.shared.api.ApiErrorResponse;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.http.HttpMethod.POST;

/**
 * What is public, what needs a token, and what a rejection looks like.
 * <p>
 * This is PayMesh's authorization boundary. It lives in {@code shared} rather than in the identity
 * module because it governs every capability, and a rule about the merchant API has no business
 * being buried in identity's package.
 * <p>
 * Authentication proves <em>who</em> the caller is. It deliberately does not prove <em>which
 * merchant</em> they may act for: that check belongs next to the data, in the services and queries
 * that carry a merchant id, because an endpoint-level rule cannot see which row is being touched.
 * See {@link AuthenticatedCaller}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        AuthenticationEntryPoint authenticationEntryPoint,
        AccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        return http
            // No cookies, no sessions, no ambient credential for a cross-site form post to ride
            // on: every request carries its own bearer token or it is anonymous.
            .csrf(csrf -> csrf.disable())
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(formLogin -> formLogin.disable())
            .logout(logout -> logout.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(requests -> requests
                // THE ERROR DISPATCH IS NOT A SECOND REQUEST TO AUTHORIZE, AND TREATING IT AS ONE
                // MAKES EVERY FAILURE LIE.
                //
                // When a request fails after this chain has already let it through, the container
                // re-dispatches it to /error, and Boot registers the security chain for the ERROR
                // dispatch type as well as REQUEST. That second pass sees the path /error, which
                // matches nothing below except anyRequest().authenticated(), and an anonymous
                // caller therefore gets 401 UNAUTHENTICATED in place of the answer it earned --
                // a 415, a 404, or, worst of all, a 500. The original request was already
                // authorized; re-judging its error page decides nothing and hides everything.
                //
                // This is not hypothetical: it reported a constraint-violation 500 on the
                // provider-callback route as an authentication failure, which is why the Postman
                // collection's callbacks looked like a filter-ordering problem for a week. See
                // ErrorDispatchSecurityTest.
                //
                // Scoped to ERROR alone. ASYNC re-dispatch carries the security context forward and
                // needs no exemption, and FORWARD is not in the chain's dispatcher types at all.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Getting a token cannot itself require a token.
                .requestMatchers("/api/v1/auth/**").permitAll()
                // Self-service onboarding: creating a merchant is the signup step that a user
                // account is then attached to, so it precedes having any credential. Deliberately
                // open, and therefore the first endpoint that needs rate limiting -- an
                // unauthenticated write is an abuse vector until Phase 2 adds one.
                .requestMatchers(POST, "/api/v1/merchants").permitAll()
                // Liveness/readiness for orchestrators. show-details is never, so these expose
                // nothing beyond UP/DOWN.
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                // PROVIDER CALLBACKS ARE NOT UNAUTHENTICATED. THEY AUTHENTICATE DIFFERENTLY.
                //
                // A provider has no PayMesh account and no bearer token, so this chain has no
                // credential to evaluate and says so explicitly -- the default-deny rule below
                // means silence here would look like an oversight. What actually stands in front of
                // the route is ProviderCallbackSignatureFilter: an HMAC-SHA256 over the raw body
                // and a timestamp, constant-time compared, fail-closed on the secret.
                //
                // It must NOT be reachable with a merchant's token either. This route moves
                // payments to SUCCEEDED, so a merchant who could call it could mark their own
                // payment collected. Placing it here rather than under /api/ is what keeps the two
                // audiences from sharing a surface at all.
                .requestMatchers(POST, "/internal/v1/provider-callbacks/**").permitAll()
                // Default deny. A new endpoint is protected by virtue of existing; opening one is
                // an explicit line above, never an omission.
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {
                })
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .build();
    }

    /**
     * Rejections from the filter chain happen before any {@code @RestControllerAdvice} runs, so
     * without these two handlers a missing token would return an empty body while every other
     * error returns {code, message, fieldErrors}. Clients should not have to parse two shapes.
     */
    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> writeError(
            response,
            objectMapper,
            HttpStatus.UNAUTHORIZED,
            "UNAUTHENTICATED",
            "A valid access token is required."
        );
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, exception) -> writeError(
            response,
            objectMapper,
            HttpStatus.FORBIDDEN,
            "ACCESS_DENIED",
            "This account may not perform that action."
        );
    }

    private static void writeError(
        jakarta.servlet.http.HttpServletResponse response,
        ObjectMapper objectMapper,
        HttpStatus status,
        String code,
        String message
    ) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(code, message));
    }
}
