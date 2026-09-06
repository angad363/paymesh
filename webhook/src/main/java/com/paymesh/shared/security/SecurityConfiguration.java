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

/**
 * What is public, what needs a token, and what a rejection looks like -- webhook's own copy
 * (ADR-042 section 3), package-identical to the monolith's so the capability's own imports never
 * moved. Not the same file: this deployable carries the JWT half of the boundary ONLY.
 * <p>
 * THE APIKEY FILTER DOES NOT COME (ADR-042 section 3). Minting a JWT from an {@code ApiKey}
 * requires reading {@code api_credentials}, which lives in the {@code merchant} schema --
 * {@code webhook_svc} cannot see it, and will not until Merchant is extracted (PR 11). A machine
 * caller presenting a raw {@code ApiKey} directly to webhook is therefore out of scope for this PR;
 * the gateway fronts every merchant-facing route, validates the JWT, and the exchange from
 * {@code ApiKey} stays a monolith-internal concern.
 * <p>
 * The matcher list is this deployable's own surface only: {@code /actuator/health/**} and
 * {@code /actuator/info} are public, the ERROR dispatch is exempted for the same reason the
 * monolith exempts it, and everything else -- which here means {@code /api/v1/webhook-endpoints/**}
 * -- is authenticated. No provider-callback, refund-callback, payout-callback, merchant-registration
 * or auth matcher belongs to this deployable, so none of them are carried over.
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
                // MAKES EVERY FAILURE LIE. Same reasoning as the monolith's SecurityConfiguration;
                // see ErrorDispatchSecurityTest there. Scoped to ERROR alone.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Liveness/readiness for orchestrators. show-details is never, so these expose
                // nothing beyond UP/DOWN.
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
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
