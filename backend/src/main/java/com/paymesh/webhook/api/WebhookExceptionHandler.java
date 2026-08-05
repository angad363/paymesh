package com.paymesh.webhook.api;

import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.NoMerchantScopeException;
import com.paymesh.webhook.application.WebhookEndpointAlreadyExistsException;
import com.paymesh.webhook.application.WebhookEndpointExceptions.TooManyWebhookEndpointsException;
import com.paymesh.webhook.application.WebhookEndpointExceptions.UnpublishedEventTypeException;
import com.paymesh.webhook.application.WebhookEndpointExceptions.WebhookDeliveryNotFoundException;
import com.paymesh.webhook.application.WebhookEndpointExceptions.WebhookEndpointNotFoundException;
import com.paymesh.webhook.domain.DeliveryNotReplayableException;
import com.paymesh.webhook.domain.SecretVersionMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scoped to the webhook controller, per the per-feature advice convention. The scoping also bounds
 * the {@code IllegalArgumentException} handler: one raised anywhere else is a bug and must stay a
 * 500 rather than be reported to the caller as their mistake.
 */
@RestControllerAdvice(assignableTypes = WebhookEndpointController.class)
public final class WebhookExceptionHandler {

    /**
     * Also the answer for another merchant's endpoint. A 403 would confirm the id exists and turn
     * this route into an oracle for enumerating other tenants' endpoints (ADR-007).
     */
    @ExceptionHandler(WebhookEndpointNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleEndpointNotFound(WebhookEndpointNotFoundException exception) {
        return ApiErrorResponse.of("WEBHOOK_ENDPOINT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(WebhookDeliveryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleDeliveryNotFound(WebhookDeliveryNotFoundException exception) {
        return ApiErrorResponse.of("WEBHOOK_DELIVERY_NOT_FOUND", exception.getMessage());
    }

    /** 409: a URL this merchant already registered. Two endpoints there would fan out twice. */
    @ExceptionHandler(WebhookEndpointAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleDuplicateUrl(WebhookEndpointAlreadyExistsException exception) {
        return ApiErrorResponse.of("WEBHOOK_ENDPOINT_ALREADY_EXISTS", exception.getMessage());
    }

    /**
     * 409: the caller named a version that is neither current nor the one just replaced, so it is
     * working from a stale view and a blind bump would invalidate a secret somebody is using.
     */
    @ExceptionHandler(SecretVersionMismatchException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleVersionMismatch(SecretVersionMismatchException exception) {
        return ApiErrorResponse.of("WEBHOOK_SECRET_VERSION_MISMATCH", exception.getMessage());
    }

    /** 409: replaying a delivery the dispatcher will retry anyway would send it twice. */
    @ExceptionHandler(DeliveryNotReplayableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleNotReplayable(DeliveryNotReplayableException exception) {
        return ApiErrorResponse.of("WEBHOOK_DELIVERY_NOT_REPLAYABLE", exception.getMessage());
    }

    /**
     * 422 rather than 409: the request is well-formed and nothing is in a conflicting state -- the
     * merchant has simply reached a ceiling, and the message says what it is.
     */
    @ExceptionHandler(TooManyWebhookEndpointsException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handleTooManyEndpoints(TooManyWebhookEndpointsException exception) {
        return ApiErrorResponse.of("TOO_MANY_WEBHOOK_ENDPOINTS", exception.getMessage());
    }

    /** 422, and the message lists what IS available -- the useful half of the answer. */
    @ExceptionHandler(UnpublishedEventTypeException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handleUnpublishedEventType(UnpublishedEventTypeException exception) {
        return ApiErrorResponse.of("UNPUBLISHED_EVENT_TYPE", exception.getMessage());
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

    /**
     * A malformed endpoint id, a plaintext URL, a URL carrying userinfo, an empty subscription list,
     * a status that is not one of two words, or a limit below 1. All of them the caller's mistake.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }
}
