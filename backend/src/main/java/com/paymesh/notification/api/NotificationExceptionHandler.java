package com.paymesh.notification.api;

import com.paymesh.notification.application.NotificationNotFoundException;
import com.paymesh.shared.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to {@link NotificationController}, per the per-feature advice convention -- which also
 * bounds the {@code IllegalArgumentException} handler to this controller, so a malformed id here is
 * the caller's 400 while the same exception raised elsewhere stays a 500.
 */
@RestControllerAdvice(assignableTypes = NotificationController.class)
public final class NotificationExceptionHandler {

    @ExceptionHandler(NotificationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleNotFound(NotificationNotFoundException exception) {
        return ApiErrorResponse.of("NOTIFICATION_NOT_FOUND", exception.getMessage());
    }

    /** A malformed notification id -- not a {@code nfn_} + UUID. The caller's mistake. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }
}
