package com.paymesh.ledger.api;

import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.NoMerchantScopeException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP translation for the balance endpoint, and it is nearly empty on purpose.
 * <p>
 * The endpoint is a read with no path variables, no body and no query parameters: there is no
 * validation to fail, no identifier to be malformed and no resource to be missing. A merchant with
 * no payments has an empty balance list, not a 404.
 * <p>
 * That leaves one case. A caller whose token carries no merchant scope, or more than one, cannot be
 * resolved to a tenant -- and the ledger must never guess which merchant's money to show.
 */
@RestControllerAdvice(assignableTypes = BalanceController.class)
public final class LedgerExceptionHandler {

    @ExceptionHandler(NoMerchantScopeException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiErrorResponse handleNoMerchantScope(NoMerchantScopeException exception) {
        return ApiErrorResponse.of("NO_MERCHANT_SCOPE", exception.getMessage());
    }
}
