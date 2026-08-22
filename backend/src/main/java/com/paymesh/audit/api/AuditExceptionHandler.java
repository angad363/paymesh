package com.paymesh.audit.api;

import com.paymesh.audit.application.AuditEventNotFoundException;
import com.paymesh.audit.application.AuditExportNotFoundException;
import com.paymesh.audit.application.AuditExportNotReadyException;
import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.InsufficientRoleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to Audit's two controllers, per the per-feature advice convention. There is no global
 * advice in this codebase (every handler is {@code assignableTypes}-scoped), so a controller that
 * calls {@code requirePlatformAdmin} MUST map {@link InsufficientRoleException} here or a merchant
 * token gets a 500 instead of a 403 -- Notification, Merchant and UserAdmin carry the same handler
 * for the same reason.
 *
 * <h2>EVERY RESPONSE IS PINNED TO JSON, AND THAT IS NOT DECORATION</h2>
 *
 * The CSV download route ({@code GET /audit-exports/{id}} under {@code Accept: text/csv}) can throw
 * before it produces a byte -- a malformed id, an unknown id, an export not yet rendered. A bare
 * {@code ApiErrorResponse} would be serialized against {@code Accept: text/csv}, fail negotiation,
 * and surface the ORIGINAL exception as a 500. Returning a {@link ResponseEntity} with an explicit
 * JSON content type selects the converter directly and bypasses {@code Accept}, so a client asking
 * for a CSV that is not ready gets a JSON 409. The same fix {@code ReportingExceptionHandler} made.
 */
@RestControllerAdvice(
    assignableTypes = {AuditEventController.class, AuditExportController.class}
)
public final class AuditExceptionHandler {

    @ExceptionHandler(AuditEventNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleEventNotFound(AuditEventNotFoundException exception) {
        return json(HttpStatus.NOT_FOUND, "AUDIT_EVENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(AuditExportNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleExportNotFound(AuditExportNotFoundException exception) {
        return json(HttpStatus.NOT_FOUND, "AUDIT_EXPORT_NOT_FOUND", exception.getMessage());
    }

    /**
     * The CSV was asked for before it was rendered. A 409 rather than a 404 because the export
     * exists -- the operator should keep polling, and a 404 would tell them to give up.
     */
    @ExceptionHandler(AuditExportNotReadyException.class)
    ResponseEntity<ApiErrorResponse> handleNotReady(AuditExportNotReadyException exception) {
        return json(HttpStatus.CONFLICT, "AUDIT_EXPORT_NOT_READY", exception.getMessage());
    }

    /** A caller without platform authority. Both audit surfaces are platform staff only. */
    @ExceptionHandler(InsufficientRoleException.class)
    ResponseEntity<ApiErrorResponse> handleInsufficientRole(InsufficientRoleException exception) {
        return json(HttpStatus.FORBIDDEN, "INSUFFICIENT_ROLE", exception.getMessage());
    }

    /**
     * A malformed window, an interval longer than a year, a bad limit, or an id that is not the
     * right prefix plus a UUID. All the caller's mistake.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidInput(IllegalArgumentException exception) {
        return json(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    private static ResponseEntity<ApiErrorResponse> json(
        HttpStatus status, String code, String message
    ) {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ApiErrorResponse.of(code, message));
    }
}
