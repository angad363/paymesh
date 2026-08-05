package com.paymesh.webhook.infrastructure.events;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookFanOutHandlerTest {

    /**
     * THE CONSUMER NAME IS A PRIMARY KEY COLUMN IN {@code processed_events}. Changing it re-opens the
     * whole backlog to this consumer, which here means re-POSTing every event ever sent to every
     * merchant. Pinned so a refactor has to argue with a test.
     */
    @Test
    void namesItselfAfterTheEventTypeAndNotAfterTheClass() {
        WebhookFanOutHandler handler =
            new WebhookFanOutHandler("payment.succeeded", null, null);

        assertThat(handler.consumerName()).isEqualTo("webhook.payment.succeeded");
        assertThat(handler.eventType()).isEqualTo("payment.succeeded");
    }

    /**
     * Subscribing to a type nothing translates would fan out and then throw on every single event of
     * that type, one dead letter at a time. Refused at construction, which is startup.
     */
    @Test
    void refusesToSubscribeToATypeNothingTranslates() {
        assertThatThrownBy(() -> new WebhookFanOutHandler("payment.created", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payment.created");
    }
}
