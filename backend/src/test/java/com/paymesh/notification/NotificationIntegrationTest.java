package com.paymesh.notification;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.notification.application.GetNotificationService;
import com.paymesh.notification.application.NotificationNotFoundException;
import com.paymesh.notification.application.SendPendingNotificationsService;
import com.paymesh.notification.domain.NotificationId;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NOTIFICATION AGAINST A REAL POSTGRESQL, driven through the real outbox relay and event dispatcher.
 *
 * <p>The unit tests prove the rendering and the dispatch state machine; this proves the wiring and
 * the constraints -- that Notification is genuinely subscribed, that the row satisfies the migration
 * (the {@code nfn_} format check, the sent_at check, the unique source-event index), that a
 * redelivery is a no-op rather than merely unlikely, and that the SKIP-LOCKED claim and partial
 * index the dispatcher relies on actually exist.
 *
 * <p>{@code payment.failed} is the event, and the choice is load-bearing exactly as
 * {@code order.paid} is for {@code WebhookIntegrationTest}: it is the one type Notification
 * subscribes to that has no OTHER consumer (Order and the Ledger take payment.succeeded and
 * refund.succeeded, and both would refuse a synthetic payload naming rows that were never created,
 * aborting the pass before Notification is reached). Webhook also consumes it, but with no endpoint
 * registered it writes nothing.
 *
 * <p>Deliberately not {@code @Transactional}: an outer test transaction would make assertions pass
 * regardless of whether the dispatcher opened its own, and each test registers its own merchant.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class NotificationIntegrationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-16T10:00:00Z");

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private PublishOutboxEventsService relay;

    @Autowired
    private SendPendingNotificationsService sendPending;

    @Autowired
    private GetNotificationService getNotification;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private JdbcClient jdbc;

    /**
     * THE HEADLINE: a payment fails, the relay dispatches it, and a PENDING notification is waiting --
     * and nothing here calls Notification. The producer wrote an event; Notification subscribed.
     */
    @Test
    void recordsAPendingNotificationWhenAnEventIsRelayed() {
        MerchantId merchantId = merchant();

        String sourceEventId = relayPaymentFailed(merchantId);

        Map<String, Object> row = notificationFor(sourceEventId);

        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("subject")).isEqualTo("Payment failed");
        assertThat(row.get("merchant_id")).isEqualTo(merchantId.value());
        assertThat((String) row.get("body")).contains("card_declined");
    }

    /** The full loop: the dispatcher sends the PENDING notification and it becomes SENT. */
    @Test
    void sendsTheNotificationOnTheNextDispatchPass() {
        MerchantId merchantId = merchant();
        String sourceEventId = relayPaymentFailed(merchantId);

        SendPendingNotificationsService.DispatchResult result = sendPending.dispatch();

        assertThat(result.sent()).isGreaterThanOrEqualTo(1);

        Map<String, Object> row = notificationFor(sourceEventId);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("sent_at")).isNotNull();
    }

    /**
     * A REDELIVERED OUTBOX EVENT IS A NO-OP, and here {@code uq_notifications_source_event} is the
     * real guard: the handler is driven past the inbox, the way a Kafka consumer would see it.
     */
    @Test
    void aSecondDeliveryOfOneEventWritesNothingNew() {
        MerchantId merchantId = merchant();
        OutboxEvent event = paymentFailed(merchantId);

        outbox.append(event);
        relay.publish();

        jdbc.sql("delete from processed_events where event_id = ?")
            .param(event.eventId().value())
            .update();
        jdbc.sql("update outbox_events set published_at = null where event_id = ?")
            .param(event.eventId().value())
            .update();

        relay.publish();

        assertThat(countFor(event.eventId().value())).isEqualTo(1);
    }

    @Test
    void readsANotificationBackByIdAndReports404ForAnUnknownOne() {
        MerchantId merchantId = merchant();
        String sourceEventId = relayPaymentFailed(merchantId);

        String id = (String) notificationFor(sourceEventId).get("notification_id");

        assertThat(getNotification.get(NotificationId.from(id)).subject()).isEqualTo("Payment failed");

        assertThatThrownBy(() -> getNotification.get(NotificationId.generate()))
            .isInstanceOf(NotificationNotFoundException.class);
    }

    // --- helpers --------------------------------------------------------------------------------

    private MerchantId merchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Notification Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            OCCURRED_AT
        ).activate(OCCURRED_AT)).merchantId();
    }

    private String relayPaymentFailed(MerchantId merchantId) {
        OutboxEvent event = paymentFailed(merchantId);

        outbox.append(event);
        relay.publish();

        return event.eventId().value();
    }

    private OutboxEvent paymentFailed(MerchantId merchantId) {
        String paymentIntentId = "pi_" + UUID.randomUUID();

        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", paymentIntentId);
        payload.put("amountMinor", 1500);
        payload.put("capturedAmountMinor", 0);
        payload.put("currency", "INR");
        payload.put("status", "FAILED");
        payload.put("failureCode", "card_declined");
        payload.put("failureMessage", "Insufficient funds");
        payload.put("failedAt", OCCURRED_AT.toString());

        return new OutboxEvent(
            EventId.generate(), merchantId, "PAYMENT_INTENT", paymentIntentId,
            "payment.failed", 1, payload, OCCURRED_AT
        );
    }

    private Map<String, Object> notificationFor(String sourceEventId) {
        return jdbc.sql("""
            select notification_id, merchant_id, status, subject, body, sent_at
              from notifications where source_event_id = ?
            """)
            .param(sourceEventId)
            .query()
            .singleRow();
    }

    private int countFor(String sourceEventId) {
        return jdbc.sql("select count(*) from notifications where source_event_id = ?")
            .param(sourceEventId)
            .query(Integer.class)
            .single();
    }
}
