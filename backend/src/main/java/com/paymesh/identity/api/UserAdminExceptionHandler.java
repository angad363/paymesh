package com.paymesh.identity.api;

import com.paymesh.identity.application.CannotRevokeOwnAccessException;
import com.paymesh.identity.application.LastPlatformAdminException;
import com.paymesh.identity.application.UserNotFoundException;
import com.paymesh.identity.domain.UserHoldsNoPlatformRoleException;
import com.paymesh.identity.domain.UserHoldsNoRoleAtMerchantException;
import com.paymesh.identity.domain.UserAlreadyHoldsRoleException;
import com.paymesh.identity.domain.UserStatusNotChangeableException;
import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.InsufficientRoleException;
import com.paymesh.shared.security.NoMerchantScopeException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = UserAdminController.class)
public final class UserAdminExceptionHandler {

    /**
     * 404 for "no such user" AND for "holds no role at your merchant".
     * <p>
     * The same answer on purpose: a merchant admin who could tell them apart could enumerate every
     * user id on the platform by watching which ones answered differently (ADR-007).
     */
    @ExceptionHandler({UserNotFoundException.class, UserHoldsNoRoleAtMerchantException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleUserNotFound(RuntimeException exception) {
        return ApiErrorResponse.of("USER_NOT_FOUND", "No such user.");
    }

    /**
     * 404: there is no such platform grant to remove.
     * <p>
     * Its own code rather than reuse of USER_NOT_FOUND, and its own message, because the
     * enumeration argument above does not apply here -- the caller is already platform staff, so
     * there is nothing they could learn that they cannot already read from the user list.
     */
    @ExceptionHandler(UserHoldsNoPlatformRoleException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleNoPlatformRole(UserHoldsNoPlatformRoleException exception) {
        return ApiErrorResponse.of("USER_HOLDS_NO_PLATFORM_ROLE", exception.getMessage());
    }

    /** 409: demoting this one would leave the platform unable to activate any merchant. */
    @ExceptionHandler(LastPlatformAdminException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleLastPlatformAdmin(LastPlatformAdminException exception) {
        return ApiErrorResponse.of("LAST_PLATFORM_ADMIN", exception.getMessage());
    }

    @ExceptionHandler(UserStatusNotChangeableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleStatusNotChangeable(UserStatusNotChangeableException exception) {
        return ApiErrorResponse.of("USER_STATUS_NOT_CHANGEABLE", exception.getMessage());
    }

    @ExceptionHandler(CannotRevokeOwnAccessException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleSelfRevocation(CannotRevokeOwnAccessException exception) {
        return ApiErrorResponse.of("CANNOT_REVOKE_OWN_ACCESS", exception.getMessage());
    }

    @ExceptionHandler(InsufficientRoleException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiErrorResponse handleInsufficientRole(InsufficientRoleException exception) {
        return ApiErrorResponse.of("INSUFFICIENT_ROLE", exception.getMessage());
    }

    @ExceptionHandler(NoMerchantScopeException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiErrorResponse handleNoMerchantScope(NoMerchantScopeException exception) {
        return ApiErrorResponse.of("NO_MERCHANT_SCOPE", exception.getMessage());
    }

    /** A malformed user id. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(UserAlreadyHoldsRoleException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleAlreadyHoldsRole(UserAlreadyHoldsRoleException exception) {
        return ApiErrorResponse.of("USER_ALREADY_HOLDS_ROLE", exception.getMessage());
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleValidationFailure(
        org.springframework.web.bind.MethodArgumentNotValidException exception
    ) {
        java.util.Map<String, String> fieldErrors = new java.util.LinkedHashMap<>();

        for (org.springframework.validation.FieldError fieldError
                : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        return ApiErrorResponse.validation(fieldErrors);
    }
}
