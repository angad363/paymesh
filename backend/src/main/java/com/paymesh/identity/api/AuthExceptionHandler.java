package com.paymesh.identity.api;

import com.paymesh.identity.application.InvalidCredentialsException;
import com.paymesh.identity.application.InvalidRefreshTokenException;
import com.paymesh.identity.application.UserEmailAlreadyExistsException;
import com.paymesh.identity.application.UserNotActiveException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scoped to the auth controller, per the per-feature advice convention. The scope
 * also bounds handleInvalidInput below: an IllegalArgumentException raised anywhere
 * else in the app is a bug and must keep surfacing as a 500 rather than being
 * reported to the caller as their mistake.
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
public final class AuthExceptionHandler {

    @ExceptionHandler(UserEmailAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleUserEmailAlreadyExists(UserEmailAlreadyExistsException exception) {
        return ApiErrorResponse.of(
            "USER_EMAIL_ALREADY_EXISTS",
            exception.getMessage()
        );
    }

    /**
     * 401, and the same body whether the email is unknown or the password is wrong.
     * A distinguishable response would turn login into an account-enumeration
     * oracle (REST conventions section 36).
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiErrorResponse handleInvalidCredentials(InvalidCredentialsException exception) {
        return ApiErrorResponse.of(
            "INVALID_CREDENTIALS",
            exception.getMessage()
        );
    }

    /**
     * 403, not 401: the caller's password verified, so they are authenticated and
     * merely not permitted to open a session. Telling them their account is
     * suspended discloses nothing they do not already own.
     */
    @ExceptionHandler(UserNotActiveException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiErrorResponse handleUserNotActive(UserNotActiveException exception) {
        return ApiErrorResponse.of(
            "USER_NOT_ACTIVE",
            exception.getMessage()
        );
    }

    /**
     * 401 for unknown, expired, already-rotated and revoked tokens alike. In
     * particular, a replayed token that just triggered family revocation gets this
     * identical response: an attacker must not learn the theft was detected.
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiErrorResponse handleInvalidRefreshToken(InvalidRefreshTokenException exception) {
        return ApiErrorResponse.of(
            "INVALID_REFRESH_TOKEN",
            exception.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleValidationFailure(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(
                fieldError.getField(),
                fieldError.getDefaultMessage()
            );
        }

        return ApiErrorResponse.validation(fieldErrors);
    }

    /**
     * Domain invariants (UserId prefix/UUID, email length, password-hash shape, ...)
     * throw IllegalArgumentException, which Bean Validation cannot express and which
     * would otherwise escape as a 500. Every one of them means the caller sent
     * something unusable, so 400 with the standard error body.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of(
            "INVALID_REQUEST",
            exception.getMessage()
        );
    }
}
