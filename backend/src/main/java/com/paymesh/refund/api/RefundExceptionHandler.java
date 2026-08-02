package com.paymesh.refund.api;

import com.paymesh.refund.application.PaymentNotRefundableException;
import com.paymesh.refund.application.RefundAlreadyRequestedException;
import com.paymesh.refund.application.RefundExceedsCapturedAmountException;
import com.paymesh.refund.application.RefundNotFoundException;
import com.paymesh.refund.domain.RefundNotInStateException;
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
 * Scoped to the merchant-facing refund controller, per the per-feature advice convention. The
 * scoping also bounds the {@code IllegalArgumentException} handler: one raised anywhere else in the
 * application is a bug and must stay a 500 rather than be reported to the caller as their mistake.
 */
@RestControllerAdvice(assignableTypes = RefundController.class)
public final class RefundExceptionHandler {

    /**
     * Also the answer for another merchant's refund. A 403 would confirm the id exists and turn
     * this endpoint into an oracle for enumerating other tenants' refunds (ADR-007).
     */
    @ExceptionHandler(RefundNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleRefundNotFound(RefundNotFoundException exception) {
        return ApiErrorResponse.of("REFUND_NOT_FOUND", exception.getMessage());
    }

    /**
     * 422, and ONE code for three causes: no such payment intent, one belonging to another
     * merchant, and one that collected nothing. Nothing is learned from the difference.
     */
    @ExceptionHandler(PaymentNotRefundableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handlePaymentNotRefundable(PaymentNotRefundableException exception) {
        return ApiErrorResponse.of("PAYMENT_NOT_REFUNDABLE", exception.getMessage());
    }

    /**
     * 422 rather than 409: the request is well-formed and the resource is in a fine state, but the
     * amount asked for is not one this payment can support. The message names all three figures so
     * a merchant can compute what would fit.
     */
    @ExceptionHandler(RefundExceedsCapturedAmountException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handleOverRefund(RefundExceedsCapturedAmountException exception) {
        return ApiErrorResponse.of("REFUND_EXCEEDS_CAPTURED_AMOUNT", exception.getMessage());
    }

    @ExceptionHandler(RefundAlreadyRequestedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleDuplicateReference(RefundAlreadyRequestedException exception) {
        return ApiErrorResponse.of("REFUND_REFERENCE_ALREADY_EXISTS", exception.getMessage());
    }

    /**
     * 409: cancelling a refund the provider already has. The state machine refused it, and the
     * message says where the refund actually is.
     */
    @ExceptionHandler(RefundNotInStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleWrongState(RefundNotInStateException exception) {
        return ApiErrorResponse.of("REFUND_NOT_CANCELLABLE", exception.getMessage());
    }

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

    /** A malformed refund id, a limit below 1, or a corrupt cursor. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }
}
