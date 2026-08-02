package com.paymesh.shared.tenant;

import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.security.AuthenticatedCallers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A SUSPENDED MERCHANT CANNOT WRITE. This is the enforcement that made merchant status mean
 * something, and before it nothing in the platform read that column at all (ADR-021).
 *
 * <h2>One filter rather than a check in every service</h2>
 *
 * There are more than a dozen authenticated write paths across five capabilities, and a rule
 * enforced in a dozen places is a rule that is missing from the thirteenth. Here it is impossible
 * to forget: a new endpoint is covered the moment it is added, without its author knowing this
 * exists.
 *
 * <h2>Writes only. Reads stay open, deliberately.</h2>
 *
 * A suspended merchant reading their own orders is not a threat, and blocking it makes the incident
 * harder for everyone to resolve -- including the operator who suspended them. What is refused is
 * anything that creates or changes state, which is every method except GET.
 *
 * <h2>What it deliberately does not guard</h2>
 *
 * <ul>
 *   <li><b>Merchant registration</b> is unauthenticated and has no merchant yet.</li>
 *   <li><b>The platform admin routes</b> -- activating and suspending a merchant necessarily act on
 *       a merchant that is not ACTIVE, so guarding them would make suspension irreversible.</li>
 *   <li><b>KYC submission</b>, because a PENDING_VERIFICATION merchant submitting verification is
 *       the one write an unverified merchant must be able to make. Refusing it would make
 *       verification unreachable, which is precisely the deadlock this whole change exists to
 *       remove.</li>
 *   <li><b>Provider and refund callbacks</b>, which are authenticated by HMAC and carry no caller.
 *       A payment already taken must settle even if the merchant was suspended in the meantime --
 *       the customer's money moved, and refusing the callback would strand it.</li>
 * </ul>
 *
 * That last one is the subtle one and it is a deliberate asymmetry: suspension stops NEW business,
 * it does not abandon business already in flight.
 */
public final class MerchantStatusFilter extends OncePerRequestFilter {

    private static final String GUARDED_PREFIX = "/api/v1/";

    /**
     * Paths that must work for a non-ACTIVE merchant, matched on the path's tail.
     *
     * <h2>EVERY ONE OF THESE WOULD OTHERWISE BE A DEADLOCK</h2>
     *
     * <ul>
     *   <li>{@code /kyc-submissions} -- the one write an unverified merchant must be able to make.
     *       Refusing it makes ACTIVE unreachable.</li>
     *   <li>{@code /activate}, {@code /suspend}, {@code /close} -- these necessarily act ON a
     *       merchant that is not ACTIVE. Guarding them would make suspension IRREVERSIBLE, because
     *       the request to lift it would be refused by the suspension itself.</li>
     *   <li>{@code /approve}, {@code /reject} -- the KYC decision, for the same reason: approval is
     *       what activates the merchant, so it cannot require the merchant to already be active.</li>
     * </ul>
     *
     * The platform routes are additionally protected by {@code requirePlatformAdmin}, so exempting
     * them here widens nothing: a merchant still cannot call them.
     */
    private static final Set<String> EXEMPT_SUFFIXES = Set.of(
        "/kyc-submissions", "/activate", "/suspend", "/close", "/approve", "/reject"
    );

    private final MerchantStatusGate gate;
    private final ObjectMapper objectMapper;

    public MerchantStatusFilter(MerchantStatusGate gate, ObjectMapper objectMapper) {
        this.gate = gate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathWithinApplication(request);

        if (!path.startsWith(GUARDED_PREFIX)) {
            return true;
        }

        if ("GET".equals(request.getMethod().toUpperCase(Locale.ROOT))) {
            return true;
        }

        // Merchant registration: unauthenticated, and there is no merchant to check.
        if (path.equals("/api/v1/merchants")) {
            return true;
        }

        return EXEMPT_SUFFIXES.stream().anyMatch(path::endsWith);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws IOException, ServletException {
        Optional<AuthenticatedCaller> caller = AuthenticatedCallers.current();

        // No caller means the security chain will refuse it anyway, or it is a route that needs no
        // tenant. Either way this filter has no opinion -- answering 403 here would pre-empt the
        // 401 the chain is about to give and tell an unauthenticated caller something.
        if (caller.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        Set<MerchantId> merchantIds = caller.get().merchantIds();

        // A caller scoped to several merchants is refused later by requireSingleMerchant with a
        // clearer message. Checking all of them here would refuse for the wrong reason.
        if (merchantIds.size() != 1) {
            chain.doFilter(request, response);
            return;
        }

        if (gate.canTransact(merchantIds.iterator().next())) {
            chain.doFilter(request, response);
            return;
        }

        forbidden(response);
    }

    /**
     * One answer, naming no status. A filter runs outside every {@code @RestControllerAdvice}, so
     * the house error body has to be written by hand here.
     * <p>
     * It does not say WHICH non-active status the merchant is in. "Suspended" versus "closed"
     * versus "not yet verified" is information about a platform decision, and the caller's next
     * step -- contact the platform -- is the same in all three.
     */
    private void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(
            "MERCHANT_NOT_ACTIVE",
            "This merchant cannot perform this action. Contact PayMesh support."
        ));
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();

        return contextPath == null || contextPath.isEmpty()
            ? uri
            : uri.substring(contextPath.length());
    }
}
