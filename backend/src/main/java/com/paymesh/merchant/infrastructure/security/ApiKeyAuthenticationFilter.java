package com.paymesh.merchant.infrastructure.security;

import com.paymesh.merchant.application.ApiCredentialRepository;
import com.paymesh.merchant.application.ApiCredentialSecrets;
import com.paymesh.merchant.domain.ApiCredential;
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
import java.security.MessageDigest;
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
 * SDD 10.3 and 11.3 specify a "Merchant API key" for creating customers and orders. Without one,
 * every integration had to authenticate as a human with a password.
 *
 * <h2>IT PRODUCES A JWT-SHAPED PRINCIPAL ON PURPOSE</h2>
 *
 * {@code AuthenticatedCallers} reads roles off a {@link Jwt} principal. Rather than teach it a
 * second principal type -- and risk the two drifting so that a key grants what a token does not --
 * this mints an in-memory {@code Jwt} carrying the same {@code roles} claim a login would produce.
 * Downstream, an API key caller and a human caller are indistinguishable, which means every
 * authorization rule written for one automatically holds for the other.
 * <p>
 * The token is never signed and never leaves the process. It is a claims carrier, not a credential.
 *
 * <h2>Constant-time comparison, and why the lookup is by prefix</h2>
 *
 * The public prefix finds the row; the secret is then compared with
 * {@link MessageDigest#isEqual}, which does not short-circuit on the first differing byte. A plain
 * {@code equals} would leak the secret one byte at a time to anyone who can measure response time.
 *
 * <h2>What a bad key does NOT do</h2>
 *
 * It does not distinguish "no such prefix" from "wrong secret" from "revoked" -- all three are one
 * 401 with one message. Telling them apart would confirm which prefixes exist, and a revoked key
 * answering differently from an unknown one tells an attacker they once had something real.
 */
public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String SCHEME = "apikey ";

    private final ApiCredentialRepository credentials;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(
        ApiCredentialRepository credentials,
        ObjectMapper objectMapper
    ) {
        this.credentials = credentials;
        this.objectMapper = objectMapper;
    }

    /** Only requests actually presenting an ApiKey scheme. Bearer tokens are the chain's business. */
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

        Optional<ApiCredential> authenticated = authenticate(presented);

        if (authenticated.isEmpty()) {
            unauthorized(response);
            return;
        }

        ApiCredential credential = authenticated.get();

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                asJwt(credential),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + credential.role().name()))
            )
        );

        // Deliberately AFTER the context is set and outside any transaction the handler opens: a
        // write on the credential row for every authenticated request would make this the hottest
        // row in the system and would put a lock on it for the duration of every payment.
        credentials.touchLastUsed(credential.apiCredentialId(), Instant.now());

        chain.doFilter(request, response);
    }

    private Optional<ApiCredential> authenticate(String presented) {
        int separator = presented.indexOf('.');

        if (separator < 1 || separator == presented.length() - 1) {
            return Optional.empty();
        }

        String publicPrefix = presented.substring(0, separator);
        String secret = presented.substring(separator + 1);

        return credentials.findByPublicPrefix(publicPrefix)
            .filter(ApiCredential::isLive)
            .filter(credential -> matches(secret, credential.secretHash()));
    }

    /**
     * Constant-time over the HASHES, not the raw secrets. Both sides are fixed-length hex, so the
     * comparison itself leaks no length information either.
     */
    private static boolean matches(String presentedSecret, String storedHash) {
        return ApiCredentialSecrets.matches(presentedSecret, storedHash);
    }

    /**
     * The same claim shape a login produces, so nothing downstream can tell the two apart.
     * <p>
     * {@code sub} is the credential id rather than a user id: an API key is not a person, and
     * putting a human's id here would attribute a machine's writes to whoever created the key.
     */
    private static Jwt asJwt(ApiCredential credential) {
        Instant now = Instant.now();

        return Jwt.withTokenValue("api-key")
            .header("alg", "none")
            .subject(credential.apiCredentialId().value())
            .claim("roles", List.of(
                credential.role().name() + ":" + credential.merchantId().value()
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
