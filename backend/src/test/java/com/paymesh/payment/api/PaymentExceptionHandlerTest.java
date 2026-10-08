package com.paymesh.payment.api;

import com.paymesh.payment.application.PaymentBlockedByRiskException;
import com.paymesh.payment.application.RiskUnavailableException;
import com.paymesh.shared.api.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RiskUnavailableException} maps to 503, DISTINCT from {@link PaymentBlockedByRiskException}
 * (422) -- ADR-044's "does not hang and does not silently allow": the closed tier of
 * {@code RiskUnavailablePolicy} throws this, and the caller must see a retryable status, not the
 * same code a real risk refusal gets.
 * <p>
 * Plain JUnit against the handler directly, reading {@code @ResponseStatus} by reflection rather
 * than standing up a full {@code MockMvc} context -- {@link PaymentIntentControllerTest} already
 * proves the filter chain and routing end to end; this proves only the one new mapping.
 */
class PaymentExceptionHandlerTest {

    private final PaymentExceptionHandler handler = new PaymentExceptionHandler();

    @Test
    void mapsRiskUnavailableTo503WithARetryableCode() throws NoSuchMethodException {
        ApiErrorResponse body = handler.handleRiskUnavailable(
            new RiskUnavailableException("pi_1", "connection refused")
        );

        assertThat(body.code()).isEqualTo("RISK_UNAVAILABLE");

        Method method = PaymentExceptionHandler.class.getDeclaredMethod(
            "handleRiskUnavailable", RiskUnavailableException.class
        );
        ResponseStatus status = method.getAnnotation(ResponseStatus.class);

        assertThat(status).isNotNull();
        assertThat(status.value()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * The distinction that makes this a separate exception in the first place: a real risk
     * refusal is 422, not 503, so a merchant does not retry a confirm that will fail again.
     */
    @Test
    void aRealRiskBlockStaysDistinctAt422() throws NoSuchMethodException {
        Method method = PaymentExceptionHandler.class.getDeclaredMethod(
            "handlePaymentBlockedByRisk", PaymentBlockedByRiskException.class
        );
        ResponseStatus status = method.getAnnotation(ResponseStatus.class);

        assertThat(status).isNotNull();
        assertThat(status.value()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
