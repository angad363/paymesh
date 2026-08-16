package com.paymesh.notification.infrastructure.events;

import com.paymesh.notification.infrastructure.events.NotificationTemplates.Rendered;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTemplatesTest {

    private final NotificationTemplates templates = new NotificationTemplates();

    @Test
    void rendersAPaymentSuccess() {
        Rendered rendered = templates.render("payment.succeeded", Map.of(
            "paymentIntentId", "pi_x", "capturedAmountMinor", 1500, "currency", "USD"
        ));

        assertThat(rendered.subject()).isEqualTo("Payment received");
        assertThat(rendered.body())
            .isEqualTo("Payment pi_x for 1500 USD (minor units) succeeded.");
    }

    @Test
    void rendersARefundSuccess() {
        Rendered rendered = templates.render("refund.succeeded", Map.of(
            "refundId", "ref_x", "paymentIntentId", "pi_x", "amountMinor", 500, "currency", "USD"
        ));

        assertThat(rendered.subject()).isEqualTo("Refund processed");
        assertThat(rendered.body())
            .isEqualTo("Refund ref_x for 500 USD (minor units) on payment pi_x was processed.");
    }

    /** Both failure fields present -> both in the trailer. */
    @Test
    void rendersAPaymentFailureWithReason() {
        Rendered rendered = templates.render("payment.failed", Map.of(
            "paymentIntentId", "pi_x", "amountMinor", 1500, "currency", "USD",
            "failureCode", "card_declined", "failureMessage", "Insufficient funds"
        ));

        assertThat(rendered.body()).isEqualTo(
            "Payment pi_x for 1500 USD (minor units) failed. (card_declined: Insufficient funds)"
        );
    }

    /**
     * The other payment.failed producer omits the failure fields (the timeout sweeper vs the
     * callback -- see WebhookPayloadTranslator). The trailer is dropped, not rendered as nulls.
     */
    @Test
    void rendersAPaymentFailureWithoutReason() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", "pi_x");
        payload.put("amountMinor", 1500);
        payload.put("currency", "USD");

        Rendered rendered = templates.render("payment.failed", payload);

        assertThat(rendered.body()).isEqualTo("Payment pi_x for 1500 USD (minor units) failed.");
    }

    @Test
    void refusesAnUnsubscribedType() {
        assertThatThrownBy(() -> templates.render("order.paid", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAPayloadMissingARequiredField() {
        assertThatThrownBy(() -> templates.render("payment.succeeded", Map.of("currency", "USD")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
