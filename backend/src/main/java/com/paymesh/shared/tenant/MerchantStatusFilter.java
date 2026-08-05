package com.paymesh.shared.tenant;

import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.security.AuthenticatedCallers;
import com.paymesh.shared.security.CallerRole;
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

    /** Every exempt route lives under this one resource. See {@link #shouldNotFilter}. */
    private static final String MERCHANT_PREFIX = "/api/v1/merchants";

    /**
     * The one MERCHANT-side path that must work for a non-ACTIVE merchant.
     *
     * <h2>REFUSING IT WOULD MAKE ACTIVE UNREACHABLE</h2>
     *
     * Submitting verification is the only write an unverified merchant needs to make, and refusing
     * it is the deadlock rather than the fix -- 84 tests said so when the gate was first built
     * without KYC (ADR-021).
     * <p>
     * The PLATFORM-side routes -- activate, suspend, close, approve, reject -- used to be listed
     * here too. They are not any more: they are covered by the platform-admin rule in
     * {@link #doFilterInternal}, which is general rather than a growing list of generic verbs. The
     * list version was already a review finding once, because a bare {@code endsWith} on
     * "/activate" or "/close" would exempt any future endpoint ending in those words.
     */
    private static final Set<String> EXEMPT_SUFFIXES = Set.of("/kyc-submissions");

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

        // SCOPED TO THE MERCHANT ROUTES, and the scoping is load-bearing rather than tidiness.
        //
        // A bare endsWith on "/activate" and "/close" would silently exempt ANY future endpoint
        // ending in those words -- POST /api/v1/customers/{id}/activate, or a subscription close --
        // from the merchant status gate, with nobody noticing until a suspended merchant used one.
        // They are generic verbs; only these routes on this resource are meant to be exempt.
        return path.startsWith(MERCHANT_PREFIX)
            && EXEMPT_SUFFIXES.stream().anyMatch(path::endsWith);
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

        // PLATFORM STAFF ARE NOT A MERCHANT TRANSACTING (ADR-024).
        //
        // Their authority is platform-wide, and the merchant they are about to act on is very often
        // NOT active -- that is the whole point of activate, and of reinstating a suspension.
        // Gating them on merchant status means every platform route anywhere in the API answers
        // 403, which is what happened to the user-suspension routes and why the merchant lifecycle
        // routes once needed a path exemption.
        //
        // IT ALSO BITES THE FIRST ADMIN HARDEST. They register through the ordinary endpoint, which
        // assigns MERCHANT_ADMIN at a merchant nothing has been able to activate -- so their token
        // carries both a platform grant and a scope at an inactive tenant. Reading the merchant
        // half here would leave them unable to activate the merchant that is blocking them.
        //
        // ASKED THROUGH isPlatformAdmin() RATHER THAN SCANNING rolesByMerchant. This filter used to
        // have its own copy of that scan, which is how V23 broke it while fixing
        // requirePlatformAdmin: platform roles moved out of that map and the copy went on reading
        // the empty half. One reader now (ADR-027).
        //
        // This widens nothing a merchant can reach: registration only ever assigns MERCHANT_ADMIN,
        // ck_api_credentials_role refuses a PLATFORM_ADMIN key outright, and ck_user_roles_scope
        // refuses to store the role at a merchant at all. The only way to hold it is for platform
        // staff to have issued it.
        if (caller.get().isPlatformAdmin()) {
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
