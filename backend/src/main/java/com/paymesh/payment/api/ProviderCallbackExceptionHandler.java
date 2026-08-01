package com.paymesh.payment.api;

import com.paymesh.payment.application.PaymentIntentNotFoundException;
import com.paymesh.shared.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Its own advice, scoped to the callback controller, and short on purpose.
 * <p>
 * <b>Almost nothing is an error here.</b> The three refusals a merchant-facing route would answer
 * with a 409 -- duplicated, superseded, not a legal transition -- are all 200 with an outcome, and
 * they never reach this class: a provider retries on any non-2xx, so a conflict returned for a
 * finished payment is an infinite retry loop (ADR-012). What is left is the one deliberate 404 and
 * the malformed-request cases.
 * <p>
 * Separate from {@code PaymentExceptionHandler} rather than folded into it because that one is
 * scoped to {@code PaymentIntentController} and its mappings are the merchant-facing answers. The
 * same exception means different things on the two routes -- {@code PaymentIntentNotFoundException}
 * is "you may not see this" there and "retry, we may not have committed it yet" here -- and one
 * advice serving both would have to pick.
 */
@RestControllerAdvice(assignableTypes = ProviderCallbackController.class)
public final class ProviderCallbackExceptionHandler {

    /**
     * THE ONE NON-2XX, AND IT IS A REQUEST TO RETRY (design section 4.1).
     * <p>
     * Nothing is stored: a callback naming an unknown intent had no effect, so there is nothing to
     * deduplicate, and writing rows keyed on a caller-chosen event id for intents that do not exist
     * is unbounded write amplification on an endpoint reachable with one shared secret. The retry the
     * 404 provokes is wanted -- the likeliest cause is a callback overtaking the transaction that
     * created the intent, and the second delivery will find it.
     * <p>
     * The enumeration-oracle argument that makes this a 404 on the merchant route does not apply:
     * the caller is the provider, and it necessarily knows which intents it was told about.
     */
    @ExceptionHandler(PaymentIntentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handlePaymentIntentNotFound(PaymentIntentNotFoundException exception) {
        return ApiErrorResponse.of("PAYMENT_INTENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleValidationFailure(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        return ApiErrorResponse.validation(fieldErrors);
    }

    /**
     * A malformed intent id, an unknown outcome, or a body naming neither an intent nor a provider
     * reference. Bounded by the advice's scope: an IllegalArgumentException raised anywhere else in
     * the application is a bug and must stay a 500 rather than be reported as the caller's mistake.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }
}
