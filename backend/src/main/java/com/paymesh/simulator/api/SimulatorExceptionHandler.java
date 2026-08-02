package com.paymesh.simulator.api;

import com.paymesh.shared.api.ApiErrorResponse;
import com.paymesh.simulator.application.IdempotencyKeyReusedException;
import com.paymesh.simulator.application.SimulatedPaymentNotFoundException;
import com.paymesh.simulator.domain.CaptureExceedsAuthorizedAmountException;
import com.paymesh.simulator.domain.RefundExceedsCapturedAmountException;
import com.paymesh.simulator.domain.SimulatedPaymentNotCapturableException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scoped to {@link SimulatorController}, per the per-feature advice convention. The scoping also
 * bounds the {@link IllegalArgumentException} handler: one raised anywhere else in the application
 * is a bug and must stay a 500 rather than be reported to the caller as their mistake.
 *
 * <h2>Every code here is prefixed SIMULATOR_ or SIMULATED_, and that is not decoration</h2>
 *
 * These bodies are read while debugging a payment flow that spans both sides of the boundary. A bare
 * {@code NOT_FOUND} or {@code IDEMPOTENCY_KEY_REUSED} would be indistinguishable from PayMesh's own
 * codes in a log, and the first question in that situation is always "which side said this".
 */
@RestControllerAdvice(assignableTypes = SimulatorController.class)
public final class SimulatorExceptionHandler {

    /**
     * No 401-vs-404 oracle argument applies here, unlike the merchant API: there is one credential
     * and no tenants, so a caller holding the key is entitled to know an id is unknown.
     */
    @ExceptionHandler(SimulatedPaymentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handleSimulatedPaymentNotFound(SimulatedPaymentNotFoundException exception) {
        return ApiErrorResponse.of("SIMULATED_PAYMENT_NOT_FOUND", exception.getMessage());
    }

    /**
     * 409: the key is known and the request differs.
     * <p>
     * <b>Deliberately not "return the original".</b> Real providers differ on this and returning the
     * original would be friendlier -- but the original may be for a different amount, and answering
     * "your 5000 payment succeeded" to a request for 50000 is a money-path lie. Failing closed
     * matches ADR-009's reasoning for the platform layer.
     */
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleIdempotencyKeyReused(IdempotencyKeyReusedException exception) {
        return ApiErrorResponse.of("SIMULATOR_IDEMPOTENCY_KEY_REUSED", exception.getMessage());
    }

    /**
     * 409: the payment is not AUTHORIZED. Nothing about the request is malformed and retrying it
     * identically will never succeed -- an AUTOMATIC payment was captured by the provider on
     * authorization and a DECLINED one never will be.
     */
    @ExceptionHandler(SimulatedPaymentNotCapturableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleNotCapturable(SimulatedPaymentNotCapturableException exception) {
        return ApiErrorResponse.of("SIMULATED_PAYMENT_NOT_CAPTURABLE", exception.getMessage());
    }

    /**
     * 422, not 409: a capture IS possible on this payment, the requested figure is simply larger
     * than what was authorized, and a smaller one would succeed.
     * <p>
     * <b>This handler is the message, not the guarantee.</b>
     * {@code ck_provider_payments_captured} is what makes over-capture impossible; without the
     * aggregate's check the same request would still fail, just as a 500 naming a PostgreSQL
     * constraint.
     */
    @ExceptionHandler(CaptureExceedsAuthorizedAmountException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handleCaptureExceedsAuthorized(CaptureExceedsAuthorizedAmountException exception) {
        return ApiErrorResponse.of("CAPTURE_EXCEEDS_AUTHORIZED_AMOUNT", exception.getMessage());
    }

    /**
     * 422 for the same reason, and with the same caveat: the application checks it under a row lock
     * for a readable answer, and {@code ck_provider_payments_refunded} is what actually refuses an
     * over-refund.
     */
    @ExceptionHandler(RefundExceedsCapturedAmountException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handleRefundExceedsCaptured(RefundExceedsCapturedAmountException exception) {
        return ApiErrorResponse.of("REFUND_EXCEEDS_CAPTURED_AMOUNT", exception.getMessage());
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

    /** A malformed {@code sim_pay_} id, an unknown method, capture method or behaviour. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }
}
