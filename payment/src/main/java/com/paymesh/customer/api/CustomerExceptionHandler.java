package com.paymesh.customer.api;

import com.paymesh.customer.application.CustomerNotFoundException;
import com.paymesh.customer.application.CustomerReferenceAlreadyExistsException;
import com.paymesh.customer.application.CustomerNotChargeableException;
import com.paymesh.customer.application.PaymentMethodAlreadyAttachedException;
import com.paymesh.customer.application.PaymentMethodTokenNotFoundException;
import com.paymesh.customer.domain.CustomerStatusNotChangeableException;
import com.paymesh.customer.domain.PaymentMethodTokenAlreadyDetachedException;
import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.InsufficientRoleException;
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

    @ExceptionHandler(InsufficientRoleException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiErrorResponse handleInsufficientRole(InsufficientRoleException exception) {
        return ApiErrorResponse.of("INSUFFICIENT_ROLE", exception.getMessage());
    }

    /** 422, not 409: the request is well formed and the customer is fine, they are just blocked. */
    @ExceptionHandler(CustomerNotChargeableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handleNotChargeable(CustomerNotChargeableException exception) {
        return ApiErrorResponse.of("CUSTOMER_NOT_CHARGEABLE", exception.getMessage());
    }

    @ExceptionHandler(PaymentMethodAlreadyAttachedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleAlreadyAttached(PaymentMethodAlreadyAttachedException exception) {
        return ApiErrorResponse.of("PAYMENT_METHOD_ALREADY_ATTACHED", exception.getMessage());
    }

    @ExceptionHandler(PaymentMethodTokenAlreadyDetachedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleAlreadyDetached(PaymentMethodTokenAlreadyDetachedException exception) {
        return ApiErrorResponse.of("PAYMENT_METHOD_ALREADY_DETACHED", exception.getMessage());
    }

    /** 404 for another merchant's token too -- 403 would confirm the id exists (ADR-007). */
    @ExceptionHandler(PaymentMethodTokenNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleTokenNotFound(PaymentMethodTokenNotFoundException exception) {
        return ApiErrorResponse.of("PAYMENT_METHOD_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(CustomerStatusNotChangeableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleStatusNotChangeable(CustomerStatusNotChangeableException exception) {
        return ApiErrorResponse.of("CUSTOMER_STATUS_NOT_CHANGEABLE", exception.getMessage());
    }
}
