package com.paymesh.ledger.api;

import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.NoMerchantScopeException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP translation for the ledger's read endpoints.
 * <p>
 * The merchant-facing balance read ({@link BalanceController}) has no path variables, no body and no
 * query parameters: there is no validation to fail and no resource to be missing. A merchant with no
 * payments has an empty balance list, not a 404. Its one case is a caller whose token carries no
 * merchant scope, or more than one, which cannot be resolved to a tenant -- the ledger must never
 * guess which merchant's money to show.
 * <p>
 * The internal read ({@link LedgerReadController}) takes a {@code merchantId} path variable, so it
 * adds the one failure a path variable brings: a malformed identifier. {@code MerchantId.from}
 * throws {@link IllegalArgumentException}, which is a caller error (400), not a server fault (500).
 * Without this mapping the unmapped exception becomes a 500 -- and since ADR-050 section 6 makes this
 * route edge-reachable, the mapping belongs here, not only at the monolith.
 */
@RestControllerAdvice(assignableTypes = {BalanceController.class, LedgerReadController.class})
public final class LedgerExceptionHandler {

    @ExceptionHandler(NoMerchantScopeException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiErrorResponse handleNoMerchantScope(NoMerchantScopeException exception) {
        return ApiErrorResponse.of("NO_MERCHANT_SCOPE", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleMalformedRequest(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }
}
