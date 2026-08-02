package com.paymesh.merchant.api;

import com.paymesh.merchant.application.KycSubmissionAlreadyOpenException;
import com.paymesh.merchant.application.KycSubmissionNotFoundException;
import com.paymesh.merchant.domain.KycSubmissionAlreadyDecidedException;
import com.paymesh.merchant.domain.MerchantStatusNotChangeableException;
import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.InsufficientRoleException;
import com.paymesh.merchant.application.MerchantEmailAlreadyExistsException;
import com.paymesh.merchant.application.MerchantNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

// Scoped to the merchant controller, per the per-feature advice convention. It also bounds
// handleInvalidInput below: an IllegalArgumentException raised anywhere else in the app is a bug
// and must keep surfacing as a 500 rather than being reported to the caller as their mistake.
@RestControllerAdvice(assignableTypes = MerchantController.class)
public final class MerchantExceptionHandler {

    @ExceptionHandler(MerchantEmailAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleMerchantEmailAlreadyExists(MerchantEmailAlreadyExistsException exception) {
        return ApiErrorResponse.of(
            "MERCHANT_EMAIL_ALREADY_EXISTS",
            exception.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleValidationFailure(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for(FieldError fieldError: exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(
                fieldError.getField(),
                fieldError.getDefaultMessage()
            );
        }
        return ApiErrorResponse.validation(fieldErrors);
    }

    /**
     * Domain invariants (MerchantId prefix/UUID, 2-letter country, 3-letter currency, ...) throw
     * IllegalArgumentException, which Bean Validation cannot express and which would otherwise
     * escape as a 500. Every one of them means the caller sent something unusable, so 400 with the
     * standard error body. The message comes from the domain and names the offending rule.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of(
            "INVALID_REQUEST",
            exception.getMessage()
        );
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleMerchantNotFound(MerchantNotFoundException exception) {
        return ApiErrorResponse.of(
            "MERCHANT_NOT_FOUND",
            exception.getMessage()
        );
    }

    /**
     * 403, and distinct from {@code NoMerchantScopeException}: that one means the caller could not
     * be resolved to a tenant at all, this means they were and simply may not do it.
     */
    @ExceptionHandler(InsufficientRoleException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiErrorResponse handleInsufficientRole(InsufficientRoleException exception) {
        return ApiErrorResponse.of("INSUFFICIENT_ROLE", exception.getMessage());
    }

    /** 409: the state machine refused the transition, and the message says where it actually is. */
    @ExceptionHandler(MerchantStatusNotChangeableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleStatusNotChangeable(MerchantStatusNotChangeableException exception) {
        return ApiErrorResponse.of("MERCHANT_STATUS_NOT_CHANGEABLE", exception.getMessage());
    }

    @ExceptionHandler(KycSubmissionAlreadyOpenException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleKycAlreadyOpen(KycSubmissionAlreadyOpenException exception) {
        return ApiErrorResponse.of("KYC_SUBMISSION_ALREADY_OPEN", exception.getMessage());
    }

    @ExceptionHandler(KycSubmissionAlreadyDecidedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleKycAlreadyDecided(KycSubmissionAlreadyDecidedException exception) {
        return ApiErrorResponse.of("KYC_SUBMISSION_ALREADY_DECIDED", exception.getMessage());
    }

    @ExceptionHandler(KycSubmissionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleKycNotFound(KycSubmissionNotFoundException exception) {
        return ApiErrorResponse.of("KYC_SUBMISSION_NOT_FOUND", exception.getMessage());
    }
}
