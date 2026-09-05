package com.paymesh.gateway.config;

import com.paymesh.gateway.ApiErrorResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

import static org.springframework.http.HttpMethod.POST;

/**
 * THE EDGE. This is where an unauthenticated call is refused before it reaches any service -- the
 * "refused at the edge" half of PR 5's verification.
 *
 * <h2>It mirrors the monolith's public/authenticated split exactly, and it has to</h2>
 *
 * The monolith does not require a bearer token everywhere: token issuance is public, self-service
 * merchant signup is public, provider/refund/payout callbacks authenticate by HMAC, and the provider
 * simulator authenticates by a shared key. If the gateway demanded a JWT on those, every one of them
 * would 401 here before the monolith's real authentication ever ran. So the permit list below is a
 * copy of {@code shared.security.SecurityConfiguration}'s: the same paths are public, and everything
 * else under the API requires a valid token at the edge.
 *
 * <p>The gateway validates the token; it does NOT authorize the merchant. Which merchant a caller may
 * act for is a per-row decision the monolith still makes next to the data (backend
 * {@code AuthenticatedCaller}). The edge proves the token is real and unexpired; nothing more.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        AuthenticationEntryPoint authenticationEntryPoint
    ) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(formLogin -> formLogin.disable())
            .logout(logout -> logout.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(requests -> requests
                // Error dispatch is not a second request to authorize -- see the monolith's chain for
                // the full story; re-judging /error would turn a downstream 429/404 into a 401.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Getting a token cannot require a token.
                .requestMatchers("/api/v1/auth/**").permitAll()
                // Self-service onboarding precedes having any credential (and is why it is the route
                // that most needs the rate limit the routes apply).
                .requestMatchers(POST, "/api/v1/merchants").permitAll()
                // The gateway's OWN liveness/readiness, for orchestrators.
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                // Provider / refund / payout callbacks authenticate by HMAC at the monolith, not by a
                // bearer token. The gateway carries no secret for them and must not demand one -- it
                // forwards and lets the monolith's signature filter decide. A merchant token must not
                // reach them either, which the monolith enforces by keeping them off /api.
                //
                // The three EXACT paths the monolith permits, not a blanket /internal/v1/** -- so the
                // edge mirrors the monolith's boundary rather than forwarding /internal traffic the
                // monolith itself default-denies.
                .requestMatchers(POST, "/internal/v1/provider-callbacks/**").permitAll()
                .requestMatchers(POST, "/internal/v1/refund-callbacks/**").permitAll()
                .requestMatchers(POST, "/internal/v1/payout-callbacks/**").permitAll()
                // The provider simulator authenticates by a shared key at the monolith, same reasoning.
                .requestMatchers("/sim/v1/**").permitAll()
                // Everything else under the API needs a valid token, proven here at the edge.
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {
                })
                .authenticationEntryPoint(authenticationEntryPoint)
            )
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(authenticationEntryPoint)
            )
            .build();
    }

    /**
     * A missing or invalid token is refused before any {@code @ControllerAdvice} could run, so the
     * body is written here -- same {@code {code, message}} shape and same {@code UNAUTHENTICATED} code
     * the monolith returns, so a client sees one 401 shape whether it hit the gateway or the service.
     */
    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> writeError(
            response, objectMapper, HttpStatus.UNAUTHORIZED,
            "UNAUTHENTICATED", "A valid access token is required.");
    }

    private static void writeError(
        HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status, String code, String message
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(code, message));
    }
}
