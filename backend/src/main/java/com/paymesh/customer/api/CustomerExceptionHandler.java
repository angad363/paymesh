package com.paymesh.customer.api;

import com.paymesh.customer.application.CustomerNotFoundException;
import com.paymesh.customer.application.CustomerReferenceAlreadyExistsException;
import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.NoMerchantScopeException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scoped to the customer controller, per the per-feature advice convention. The scoping also bounds
 * the IllegalArgumentException handler: one raised anywhere else in the application is a bug and
 * must stay a 500 rather than be reported to the caller as their mistake.
 */
@RestControllerAdvice(assignableTypes = CustomerController.class)
public final class CustomerExceptionHandler {

    @ExceptionHandler(CustomerNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleCustomerNotFound(CustomerNotFoundException exception) {
        return ApiErrorResponse.of("CUSTOMER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(CustomerReferenceAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleCustomerReferenceAlreadyExists(
        CustomerReferenceAlreadyExistsException exception
    ) {
        return ApiErrorResponse.of("CUSTOMER_REFERENCE_ALREADY_EXISTS", exception.getMessage());
    }

    /**
     * Authenticated, but with no single merchant to act for. Authentication succeeded, so this is a
     * 403 rather than a 401 -- retrying with a fresh token will not help.
     */
    @ExceptionHandler(NoMerchantScopeException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiErrorResponse handleNoMerchantScope(NoMerchantScopeException exception) {
        return ApiErrorResponse.of("NO_MERCHANT_SCOPE", exception.getMessage());
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

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }
}
