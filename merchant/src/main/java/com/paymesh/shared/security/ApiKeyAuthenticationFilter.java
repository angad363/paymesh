package com.paymesh.shared.security;

import com.paymesh.shared.api.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * SERVER-TO-SERVER AUTHENTICATION, which the platform did not have.
 *
 * <pre>
 *   Authorization: ApiKey ak_&lt;prefix&gt;.&lt;secret&gt;
 * </pre>
 *
 * SDD §10.3 and §11.3 specify a "Merchant API key" for creating customers and orders. There was no
 * such thing, so every integration had to authenticate as a human with a password -- the one
 * credential a merchant's backend must never hold.
 *
 * <h2>IT RUNS INSIDE THE SECURITY CHAIN, AND IT HAS TO</h2>
 *
 * The chain ends with {@code .anyRequest().authenticated()}. A filter registered as an ordinary
 * {@code FilterRegistrationBean} runs <b>after</b> the chain, so the request would already have
 * been refused with 401 before this ever saw the header. It is added with
 * {@code addFilterBefore(..., BearerTokenAuthenticationFilter.class)} instead.
 *
 * <h2>IT PRODUCES A JWT-SHAPED PRINCIPAL ON PURPOSE</h2>
 *
 * {@code AuthenticatedCallers} reads roles off a {@link Jwt}. Rather than teach it a second
 * principal type -- and risk the two drifting so a key grants what a token does not -- this mints
 * an in-memory {@code Jwt} carrying the same {@code roles} claim a login produces. Downstream, an
 * API-key caller and a human caller are <b>indistinguishable</b>, so every authorization rule
 * written for one automatically holds for the other, including the merchant status gate.
 * <p>
 * The token is never signed and never leaves the process. It is a claims carrier, not a credential.
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String SCHEME = "apikey ";

    private final ApiKeyAuthenticator authenticator;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(
        ApiKeyAuthenticator authenticator,
        ObjectMapper objectMapper
    ) {
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
    }

    /** Only requests actually presenting the ApiKey scheme. Bearer tokens are the chain's business. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String header = request.getHeader(HEADER);

        return header == null || !header.toLowerCase(Locale.ROOT).startsWith(SCHEME);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws IOException, ServletException {
        String presented = request.getHeader(HEADER).substring(SCHEME.length()).trim();

        Optional<ApiKeyIdentity> identity = authenticator.authenticate(presented);

        if (identity.isEmpty()) {
            // ANSWERED HERE RATHER THAN LEFT TO THE CHAIN. Falling through unauthenticated would
            // produce the chain's generic UNAUTHENTICATED "a valid access token is required",
            // which sends an integrator looking for a token problem when their key is the issue.
            unauthorized(response);
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                asJwt(identity.get()),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + identity.get().role().name()))
            )
        );

        chain.doFilter(request, response);
    }

    /** The same claim shape a login produces, so nothing downstream can tell the two apart. */
    private static Jwt asJwt(ApiKeyIdentity identity) {
        Instant now = Instant.now();

        return Jwt.withTokenValue("api-key")
            .header("alg", "none")
            .subject(identity.subject())
            .claim("roles", List.of(
                identity.role().name() + ":" + identity.merchantId().value()
            ))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .build();
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(
            "API_KEY_INVALID",
            "The API key could not be verified."
        ));
    }
}
