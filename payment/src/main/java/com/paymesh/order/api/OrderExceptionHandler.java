package com.paymesh.order.api;

import com.paymesh.order.application.CustomerNotFoundForOrderException;
import com.paymesh.order.application.OrderNotFoundException;
import com.paymesh.order.application.OrderReferenceAlreadyExistsException;
import com.paymesh.order.domain.OrderNotCancellableException;
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
 * Scoped to the order controller, per the per-feature advice convention. The scoping also bounds
 * the IllegalArgumentException handler: one raised anywhere else in the application is a bug and
 * must stay a 500 rather than be reported to the caller as their mistake.
 */
@RestControllerAdvice(assignableTypes = OrderController.class)
public final class OrderExceptionHandler {

    /**
     * Also the answer for another merchant's order. A 403 would confirm the id exists and turn this
     * endpoint into an oracle for enumerating other tenants' orders (ADR-007).
     */
    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleOrderNotFound(OrderNotFoundException exception) {
        return ApiErrorResponse.of("ORDER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(OrderReferenceAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleOrderReferenceAlreadyExists(OrderReferenceAlreadyExistsException exception) {
        return ApiErrorResponse.of("ORDER_REFERENCE_ALREADY_EXISTS", exception.getMessage());
    }

    /**
     * The request was understood and is legal in form; the order is simply in a state that cannot
     * reach CANCELLED. 409 rather than 400: retrying the identical request will never succeed.
     */
    @ExceptionHandler(OrderNotCancellableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleOrderNotCancellable(OrderNotCancellableException exception) {
        return ApiErrorResponse.of("ORDER_NOT_CANCELLABLE", exception.getMessage());
    }

    /**
     * 422, not 404: the ORDER route was found, and it is the referenced customer inside a
     * well-formed body that cannot be resolved. Identical whether the customer never existed or
     * belongs to another merchant.
     */
    @ExceptionHandler(CustomerNotFoundForOrderException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handleCustomerNotFound(CustomerNotFoundForOrderException exception) {
        return ApiErrorResponse.of("CUSTOMER_NOT_FOUND", exception.getMessage());
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

    /** A malformed order id, an unknown status filter, a limit below 1, or a corrupt cursor. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }
}
