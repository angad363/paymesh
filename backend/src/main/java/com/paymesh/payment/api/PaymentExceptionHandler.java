package com.paymesh.payment.api;

import com.paymesh.payment.application.OrderHasActivePaymentIntentException;
import com.paymesh.payment.application.OrderNotPayableException;
import com.paymesh.payment.application.PaymentAmountMismatchException;
import com.paymesh.payment.application.PaymentAttemptAlreadyStartedException;
import com.paymesh.payment.application.PaymentIntentNotFoundException;
import com.paymesh.payment.domain.CaptureAmountExceedsAuthorizedException;
import com.paymesh.payment.application.PaymentBlockedByRiskException;
import com.paymesh.payment.domain.PaymentIntentNotCancellableException;
import com.paymesh.payment.domain.PaymentIntentNotCapturableException;
import com.paymesh.payment.domain.PaymentIntentNotConfirmableException;
import com.paymesh.payment.domain.PaymentMethodNotAttachableException;
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
 * Scoped to the payment intent controller, per the per-feature advice convention. The scoping also
 * bounds the IllegalArgumentException handler: one raised anywhere else in the application is a bug
 * and must stay a 500 rather than be reported to the caller as their mistake.
 */
@RestControllerAdvice(assignableTypes = PaymentIntentController.class)
public final class PaymentExceptionHandler {

    /**
     * Also the answer for another merchant's intent. A 403 would confirm the id exists and turn this
     * endpoint into an oracle for enumerating other tenants' payments (ADR-007).
     */
    @ExceptionHandler(PaymentIntentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse handlePaymentIntentNotFound(PaymentIntentNotFoundException exception) {
        return ApiErrorResponse.of("PAYMENT_INTENT_NOT_FOUND", exception.getMessage());
    }

    /**
     * 422, not 404: the payment-intent route was found, and it is the referenced order inside a
     * well-formed body that cannot be collected against. ONE CODE FOR THREE CAUSES -- no such order,
     * another merchant's order, and an order that is not PENDING -- because distinguishing them
     * would let a caller enumerate another tenant's order ids.
     */
    @ExceptionHandler(OrderNotPayableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handleOrderNotPayable(OrderNotPayableException exception) {
        return ApiErrorResponse.of("ORDER_NOT_PAYABLE", exception.getMessage());
    }

    /**
     * The body is well-formed and the order is payable; the amount simply is not the order's. 422
     * rather than 400 for the same reason: nothing about the request is malformed.
     */
    @ExceptionHandler(PaymentAmountMismatchException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handlePaymentAmountMismatch(PaymentAmountMismatchException exception) {
        return ApiErrorResponse.of("PAYMENT_AMOUNT_MISMATCH", exception.getMessage());
    }

    /**
     * 409: the order already holds a live intent and retrying the identical request will never
     * succeed until that one reaches FAILED or CANCELLED. The merchant's route forward is to cancel
     * it.
     */
    @ExceptionHandler(OrderHasActivePaymentIntentException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handleOrderHasActivePaymentIntent(OrderHasActivePaymentIntentException exception) {
        return ApiErrorResponse.of("ORDER_HAS_ACTIVE_PAYMENT_INTENT", exception.getMessage());
    }

    /**
     * The request was understood and is legal in form; the intent is simply in a state that cannot
     * reach CANCELLED. 409 rather than 400: retrying the identical request will never succeed.
     */
    @ExceptionHandler(PaymentIntentNotCancellableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handlePaymentIntentNotCancellable(PaymentIntentNotCancellableException exception) {
        return ApiErrorResponse.of("PAYMENT_INTENT_NOT_CANCELLABLE", exception.getMessage());
    }

    /**
     * The intent is past the point of choosing an instrument. 409 rather than 400: nothing about the
     * request is malformed, and retrying it identically will never succeed.
     */
    @ExceptionHandler(PaymentMethodNotAttachableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handlePaymentMethodNotAttachable(PaymentMethodNotAttachableException exception) {
        return ApiErrorResponse.of("PAYMENT_METHOD_NOT_ATTACHABLE", exception.getMessage());
    }

    /**
     * Most often: no payment method has been attached yet. 409, and the route forward is a real one
     * -- attach, then confirm.
     */
    @ExceptionHandler(PaymentIntentNotConfirmableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handlePaymentIntentNotConfirmable(PaymentIntentNotConfirmableException exception) {
        return ApiErrorResponse.of("PAYMENT_INTENT_NOT_CONFIRMABLE", exception.getMessage());
    }

    /**
     * Risk refused this confirm (ADR-030).
     *
     * <p>422 rather than 409: nothing is in conflict and nothing about the request is malformed --
     * the request is well-formed and understood, and PayMesh declines to act on it. That is
     * precisely what 422 is for, and it is the same reading {@code rest-api-conventions.md} gives.
     *
     * <p><b>The body names the assessment and not the rule.</b> An error that says WHICH rule
     * refused a payment is a free oracle: retry, vary one input, watch the message change, and the
     * ruleset is mapped. The {@code rsk_} id lets support answer "why was this refused?" from the
     * database, where the reasons belong. Same instinct as {@code ORDER_NOT_PAYABLE} collapsing
     * three causes into one code.
     *
     * <p>The intent is untouched and still confirmable -- see the exception's own javadoc for why a
     * denylist hit does not burn it.
     */
    @ExceptionHandler(PaymentBlockedByRiskException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handlePaymentBlockedByRisk(PaymentBlockedByRiskException exception) {
        return ApiErrorResponse.of(
            "PAYMENT_BLOCKED_BY_RISK",
            "This payment was refused by risk evaluation " + exception.assessmentId()
        );
    }

    /**
     * Two confirms raced and this one lost to {@code uq_payment_attempts_intent_number}. 409 rather
     * than 500: the collection the caller asked for is already under way, which is something they
     * can act on.
     */
    @ExceptionHandler(PaymentAttemptAlreadyStartedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handlePaymentAttemptAlreadyStarted(PaymentAttemptAlreadyStartedException exception) {
        return ApiErrorResponse.of("PAYMENT_ATTEMPT_ALREADY_STARTED", exception.getMessage());
    }

    /**
     * The intent is not AUTHORIZED, or it captures automatically and the provider owns the
     * collection. 409 rather than 400: nothing about the request is malformed, and retrying it
     * identically will never succeed. The message says which of the two it was.
     */
    @ExceptionHandler(PaymentIntentNotCapturableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse handlePaymentIntentNotCapturable(PaymentIntentNotCapturableException exception) {
        return ApiErrorResponse.of("PAYMENT_INTENT_NOT_CAPTURABLE", exception.getMessage());
    }

    /**
     * 422, not 400 and not 409: the body is well-formed and a capture IS possible on this intent --
     * the requested figure is simply larger than what was authorized. Same reasoning as
     * {@code PAYMENT_AMOUNT_MISMATCH}, and a smaller amount would succeed, which is why it is not a
     * conflict.
     * <p>
     * <b>This handler is the message, not the guarantee.</b> {@code ck_payment_intents_captured} is
     * what makes overcapture impossible; without the aggregate's check the same request would still
     * fail, just as a 500 naming a PostgreSQL index.
     */
    @ExceptionHandler(CaptureAmountExceedsAuthorizedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse handleCaptureAmountExceedsAuthorized(CaptureAmountExceedsAuthorizedException exception) {
        return ApiErrorResponse.of("CAPTURE_AMOUNT_EXCEEDS_AUTHORIZED", exception.getMessage());
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

    /**
     * A malformed intent id, an unknown status filter or capture method, a limit below 1, a corrupt
     * cursor, or a customer that is not the order's.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidInput(IllegalArgumentException exception) {
        return ApiErrorResponse.of("INVALID_REQUEST", exception.getMessage());
    }
}
