package com.paymesh.reporting.api;

import com.paymesh.reporting.application.ReportExportNotFoundException;
import com.paymesh.reporting.application.ReportExportNotReadyException;
import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.shared.security.NoMerchantScopeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to this capability's two controllers, per the per-feature advice convention -- which also
 * bounds the {@code IllegalArgumentException} handler to them, so a malformed window here is the
 * caller's 400 while the same exception raised elsewhere stays a 500.
 *
 * <p>There is no global advice in this codebase (every handler is {@code assignableTypes}-scoped),
 * so anything a Reporting controller can throw has to be mapped HERE or the caller gets a 500. That
 * includes {@link NoMerchantScopeException}, which a platform-admin token reaches
 * {@code requireSingleMerchant()} with -- these are merchant reports, and platform staff hold no
 * merchant scope to report on.
 *
 * <h2>EVERY RESPONSE IS PINNED TO JSON, AND THAT IS NOT DECORATION</h2>
 *
 * The CSV download route ({@code GET /{id}} with {@code produces = text/csv}) can throw all three of
 * these before it produces a byte -- a malformed id, an unknown id, an export not yet rendered. If
 * the advice returned a bare {@code ApiErrorResponse}, Spring would try to serialize it against the
 * request's {@code Accept: text/csv} and fail negotiation, and the ORIGINAL exception would surface
 * as a 500 -- which MockMvc caught. Returning a {@link ResponseEntity} with an explicit JSON
 * content type selects the converter directly and bypasses {@code Accept}, so a client asking for a
 * CSV that is not ready gets a JSON 409 rather than a 500.
 */
@RestControllerAdvice(
    assignableTypes = {ReportController.class, ReportExportController.class}
)
public final class ReportingExceptionHandler {

    @ExceptionHandler(ReportExportNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNotFound(ReportExportNotFoundException exception) {
        return json(HttpStatus.NOT_FOUND, "REPORT_EXPORT_NOT_FOUND", exception.getMessage());
    }

    /**
     * The CSV was asked for before it was rendered. A 409 rather than a 404 because the export
     * exists -- the merchant should keep polling, and a 404 would tell them to give up.
     */
    @ExceptionHandler(ReportExportNotReadyException.class)
    ResponseEntity<ApiErrorResponse> handleNotReady(ReportExportNotReadyException exception) {
        return json(HttpStatus.CONFLICT, "REPORT_EXPORT_NOT_READY", exception.getMessage());
    }

    /** A caller with no merchant scope -- platform staff. These reports belong to a tenant. */
    @ExceptionHandler(NoMerchantScopeException.class)
    ResponseEntity<ApiErrorResponse> handleNoMerchantScope(NoMerchantScopeException exception) {
        return json(HttpStatus.FORBIDDEN, "NO_MERCHANT_SCOPE", exception.getMessage());
    }

    /**
     * A malformed window, an interval longer than a year, or an export id that is not {@code rex_}
     * plus a UUID. All three are the caller's mistake.
     *
     * <p>400 for the malformed id, following Notification rather than Settlement -- which answers
     * 404 there, on the argument that distinguishing "wrong shape" from "well-shaped and unknown"
     * leaks which ids could exist. That argument does not reach here: a window and an id share this
     * handler, and answering 404 for a window nobody could parse would be worse than the leak. The
     * divergence is deliberate and worth knowing about.
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
