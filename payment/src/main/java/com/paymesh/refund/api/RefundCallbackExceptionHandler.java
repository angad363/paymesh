package com.paymesh.refund.api;

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
 * SEPARATE FROM THE MERCHANT-FACING ADVICE, AND THE SEPARATION IS THE POINT.
 * <p>
 * This route answers a provider, not a merchant, and the two audiences must not share an error
 * vocabulary. {@code RefundExceptionHandler} maps a missing refund to 404 -- correct for a merchant
 * reading their own resource, and an enumeration oracle here, where the caller is authenticated by
 * a shared secret rather than as a tenant. The callback service therefore never throws for an
 * unknown refund; it answers {@code NOT_APPLICABLE} with a 200.
 * <p>
 * What is left is the malformed body, which is a genuine 400 and tells a provider nothing about
 * what exists.
 */
@RestControllerAdvice(assignableTypes = RefundCallbackController.class)
public final class RefundCallbackExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleValidationFailure(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        return ApiErrorResponse.validation(fieldErrors);
    }

    /** An unparseable outcome or a malformed refund id in the body. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }
}
