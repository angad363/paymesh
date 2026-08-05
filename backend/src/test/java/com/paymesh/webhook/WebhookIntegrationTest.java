package com.paymesh.webhook;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.application.ListWebhookDeliveriesService;
import com.paymesh.webhook.application.RegisterWebhookEndpointCommand;
import com.paymesh.webhook.application.RegisterWebhookEndpointService;
import com.paymesh.webhook.application.RegisteredWebhookEndpoint;
import com.paymesh.webhook.application.ReplayWebhookDeliveryService;
import com.paymesh.webhook.application.RotateWebhookSecretService;
import com.paymesh.webhook.application.UpdateWebhookEndpointCommand;
import com.paymesh.webhook.application.UpdateWebhookEndpointService;
import com.paymesh.webhook.application.WebhookDeliveryRepository;
import com.paymesh.webhook.application.WebhookEndpointAlreadyExistsException;
import com.paymesh.webhook.application.WebhookEndpointExceptions.TooManyWebhookEndpointsException;
import com.paymesh.webhook.application.WebhookEndpointExceptions.UnpublishedEventTypeException;
import com.paymesh.webhook.application.WebhookEndpointExceptions.WebhookEndpointNotFoundException;
import com.paymesh.webhook.application.WebhookEventRepository;
import com.paymesh.webhook.domain.DeliveryNotReplayableException;
import com.paymesh.webhook.domain.DeliveryStatus;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.SecretVersionMismatchException;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE FAN-OUT AGAINST A REAL POSTGRESQL, driven through the real outbox relay and event dispatcher.
 *
 * <p>The unit tests prove the rules; this proves the wiring and the constraints -- that Webhook is
 * genuinely subscribed, that the jsonb subscription filter finds the right endpoints, that the
 * unique indexes make a redelivery a no-op rather than merely unlikely, and that the optimistic
 * version on the endpoint is real.
 *
 * <p>No delivery is sent here: the dispatcher is off under {@code dev} and the sender is never
 * invoked. That is the invariant, not a gap -- everything below writes rows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class WebhookIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-05T10:00:00Z");

    @Autowired
    private RegisterWebhookEndpointService registerEndpoint;

    @Autowired
    private UpdateWebhookEndpointService updateEndpoint;

    @Autowired
    private RotateWebhookSecretService rotateSecret;

    @Autowired
    private ListWebhookDeliveriesService listDeliveries;

    @Autowired
    private ReplayWebhookDeliveryService replayDelivery;

    @Autowired
    private WebhookEventRepository events;

    @Autowired
    private WebhookDeliveryRepository deliveries;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private PublishOutboxEventsService relay;

    @Autowired
    private JdbcClient jdbc;

    // --- the loop ---------------------------------------------------------------------------------

    /**
     * THE HEADLINE: an order is paid, the relay dispatches it, and a delivery is waiting for the one
     * merchant who asked. Two modules move and Order has never heard of Webhook.
     */
    @Test
    void queuesADeliveryWhenAnEventTheMerchantSubscribedToIsRelayed() {
        MerchantId merchantId = merchant();
        WebhookEndpoint endpoint = endpoint(merchantId, "order.paid").endpoint();

        String sourceEventId = relayOrderPaid(merchantId);

        WebhookEvent event = events.findBySourceEventId(sourceEventId).orElseThrow();

        assertThat(event.eventType()).isEqualTo("order.paid");
        assertThat(event.payload())
            .as("the stored bytes are the external shape, not the internal one")
            .contains("\"schemaVersion\":1")
            .contains("\"amountPaidMinor\":500000")
            .doesNotContain("previousStatus")
            .doesNotContain("merchantId");

        List<WebhookDelivery> queued =
            listDeliveries.list(merchantId, endpoint.endpointId(), null);

        assertThat(queued).hasSize(1);
        assertThat(queued.getFirst().status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(queued.getFirst().nextAttemptAt()).isNotNull();
    }

    /** The subscription filter runs over a jsonb column, which is the part that could silently fail. */
    @Test
    void doesNotQueueForAnEndpointSubscribedToADifferentType() {
        MerchantId merchantId = merchant();
        WebhookEndpoint endpoint = endpoint(merchantId, "payment.succeeded").endpoint();

        relayOrderPaid(merchantId);

        assertThat(listDeliveries.list(merchantId, endpoint.endpointId(), null)).isEmpty();
    }

    /** An event with no subscriber writes no webhook_events row either. */
    @Test
    void writesNothingForAMerchantWithNoEndpoints() {
        MerchantId merchantId = merchant();

        String sourceEventId = relayOrderPaid(merchantId);

        assertThat(events.findBySourceEventId(sourceEventId)).isEmpty();
    }

    /** Endpoints are per tenant, and so is the fan-out. */
    @Test
    void doesNotQueueForAnotherMerchantsEndpoint() {
        MerchantId subscriber = merchant();
        MerchantId payer = merchant();

        WebhookEndpoint endpoint = endpoint(subscriber, "order.paid").endpoint();

        relayOrderPaid(payer);

        assertThat(listDeliveries.list(subscriber, endpoint.endpointId(), null)).isEmpty();
    }

    /**
     * A REDELIVERED OUTBOX EVENT IS A NO-OP, and here the unique indexes are the real ones.
     * <p>
     * The inbox already stops a second dispatch of the same event, so this reaches past it: the
     * fan-out is invoked twice with one source event id, the way a Kafka consumer would see it.
     */
    @Test
    void aSecondFanOutOfOneEventWritesNothingNew() {
        MerchantId merchantId = merchant();
        WebhookEndpoint endpoint = endpoint(merchantId, "order.paid").endpoint();

        OutboxEvent event = orderPaid(merchantId);

        dispatchTwice(event);

        assertThat(listDeliveries.list(merchantId, endpoint.endpointId(), null)).hasSize(1);
        assertThat(countEventsFor(event.eventId().value())).isEqualTo(1);
    }

    // --- the endpoint API rules -------------------------------------------------------------------

    @Test
    void showsTheSecretOnceAndDerivesTheSameOneAgainOnRotation() {
        MerchantId merchantId = merchant();
        RegisteredWebhookEndpoint registered = endpoint(merchantId, "order.paid");

        assertThat(registered.secret()).startsWith("pmsec_");
        assertThat(registered.endpoint().secretVersion()).isEqualTo(1);

        RegisteredWebhookEndpoint rotated =
            rotateSecret.rotate(merchantId, registered.endpoint().endpointId(), 1);

        assertThat(rotated.endpoint().secretVersion()).isEqualTo(2);
        assertThat(rotated.secret()).isNotEqualTo(registered.secret());
        assertThat(rotated.endpoint().previousSecretExpiresAt()).isNotNull();
    }

    /**
     * A RETRIED ROTATION RE-DERIVES THE SAME SECRET, which is what lets this route stay off the
     * idempotency filter -- and off it is where it must stay, because that filter persists response
     * bodies verbatim and this response carries a secret.
     */
    @Test
    void rotatingTwiceFromTheSameVersionIsIdempotent() {
        MerchantId merchantId = merchant();
        EndpointId endpointId = endpoint(merchantId, "order.paid").endpoint().endpointId();

        RegisteredWebhookEndpoint first = rotateSecret.rotate(merchantId, endpointId, 1);
        RegisteredWebhookEndpoint retry = rotateSecret.rotate(merchantId, endpointId, 1);

        assertThat(retry.secret()).isEqualTo(first.secret());
        assertThat(retry.endpoint().secretVersion()).isEqualTo(2);
    }

    @Test
    void refusesToRotateFromAVersionThatIsNeitherCurrentNorJustReplaced() {
        MerchantId merchantId = merchant();
        EndpointId endpointId = endpoint(merchantId, "order.paid").endpoint().endpointId();

        assertThatThrownBy(() -> rotateSecret.rotate(merchantId, endpointId, 7))
            .isInstanceOf(SecretVersionMismatchException.class);
    }

    @Test
    void refusesASecondEndpointAtOneUrl() {
        MerchantId merchantId = merchant();
        String url = "https://merchant.test/" + UUID.randomUUID();

        registerEndpoint.register(merchantId, new RegisterWebhookEndpointCommand(
            url, List.of("payment.succeeded")
        ));

        assertThatThrownBy(() -> registerEndpoint.register(merchantId,
            new RegisterWebhookEndpointCommand(url, List.of("payment.succeeded"))
        )).isInstanceOf(WebhookEndpointAlreadyExistsException.class);
    }

    @Test
    void refusesASubscriptionToATypePaymeshDoesNotPublish() {
        MerchantId merchantId = merchant();

        assertThatThrownBy(() -> registerEndpoint.register(merchantId,
            new RegisterWebhookEndpointCommand(
                "https://merchant.test/" + UUID.randomUUID(), List.of("payment.created")
            )
        )).isInstanceOf(UnpublishedEventTypeException.class);
    }

    @Test
    void refusesMoreEndpointsThanTheFanOutCeilingAllows() {
        MerchantId merchantId = merchant();

        for (int i = 0; i < WebhookEndpoint.MAX_ENDPOINTS_PER_MERCHANT; i++) {
            endpoint(merchantId, "order.paid");
        }

        assertThatThrownBy(() -> endpoint(merchantId, "order.paid"))
            .isInstanceOf(TooManyWebhookEndpointsException.class);
    }

    /** A disabled endpoint stops receiving; re-enabling clears the streak the disable came from. */
    @Test
    void disablingAnEndpointStopsTheFanOut() {
        MerchantId merchantId = merchant();
        EndpointId endpointId = endpoint(merchantId, "order.paid").endpoint().endpointId();

        updateEndpoint.update(merchantId, endpointId,
            new UpdateWebhookEndpointCommand(null, "DISABLED"));

        relayOrderPaid(merchantId);

        assertThat(listDeliveries.list(merchantId, endpointId, null)).isEmpty();
    }

    @Test
    void resubscribingReplacesTheListRatherThanAddingToIt() {
        MerchantId merchantId = merchant();
        EndpointId endpointId =
            endpoint(merchantId, "payment.succeeded", "order.paid").endpoint().endpointId();

        WebhookEndpoint updated = updateEndpoint.update(merchantId, endpointId,
            new UpdateWebhookEndpointCommand(List.of("payment.succeeded"), null));

        assertThat(updated.subscriptions())
            .as("a replacement, not an addition -- order.paid is gone")
            .containsExactly("payment.succeeded");

        relayOrderPaid(merchantId);

        assertThat(listDeliveries.list(merchantId, endpointId, null)).isEmpty();
    }

    @Test
    void answers404ForAnotherMerchantsEndpoint() {
        MerchantId owner = merchant();
        MerchantId stranger = merchant();

        EndpointId endpointId = endpoint(owner, "payment.succeeded").endpoint().endpointId();

        assertThatThrownBy(() -> listDeliveries.list(stranger, endpointId, null))
            .isInstanceOf(WebhookEndpointNotFoundException.class);
    }

    // --- replay -----------------------------------------------------------------------------------

    @Test
    void refusesToReplayADeliveryTheDispatcherWillRetryAnyway() {
        MerchantId merchantId = merchant();
        EndpointId endpointId = endpoint(merchantId, "order.paid").endpoint().endpointId();

        relayOrderPaid(merchantId);

        WebhookDelivery pending = listDeliveries.list(merchantId, endpointId, null).getFirst();

        assertThatThrownBy(() ->
            replayDelivery.replay(merchantId, endpointId, pending.deliveryId())
        ).isInstanceOf(DeliveryNotReplayableException.class);
    }

    @Test
    void replaysATerminalDeliveryAndResetsItsBudget() {
        MerchantId merchantId = merchant();
        EndpointId endpointId = endpoint(merchantId, "order.paid").endpoint().endpointId();

        relayOrderPaid(merchantId);

        WebhookDelivery queued = listDeliveries.list(merchantId, endpointId, null).getFirst();

        deliveries.save(queued.succeeded(200, "ok", CREATED_AT));

        WebhookDelivery replayed =
            replayDelivery.replay(merchantId, endpointId, queued.deliveryId());

        assertThat(replayed.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(replayed.attempts()).isZero();
    }

    // --- helpers ----------------------------------------------------------------------------------

    private MerchantId merchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Webhook Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        ).activate(CREATED_AT)).merchantId();
    }

    private RegisteredWebhookEndpoint endpoint(MerchantId merchantId, String... subscriptions) {
        return registerEndpoint.register(merchantId, new RegisterWebhookEndpointCommand(
            "https://merchant.test/" + UUID.randomUUID(), List.of(subscriptions)
        ));
    }

    /** Appends and relays one order.paid, returning the outbox id it was written under. */
    private String relayOrderPaid(MerchantId merchantId) {
        OutboxEvent event = orderPaid(merchantId);

        outbox.append(event);
        relay.publish();

        return event.eventId().value();
    }

    /**
     * Reaches past the inbox on purpose: appending the same event twice under two ids would be two
     * different events, and appending one twice is refused by the primary key. Two outbox rows with
     * one {@code source_event_id} is not reachable through the relay, so the handler is driven
     * directly -- which is how a Kafka consumer would see a redelivery.
     */
    private void dispatchTwice(OutboxEvent event) {
        outbox.append(event);
        relay.publish();

        jdbc.sql("delete from processed_events where event_id = ?")
            .param(event.eventId().value())
            .update();

        jdbc.sql("update outbox_events set published_at = null where event_id = ?")
            .param(event.eventId().value())
            .update();

        relay.publish();
    }

    /**
     * {@code order.paid} rather than {@code payment.succeeded}, and the choice is load-bearing.
     *
     * <p>Webhook is not the only consumer of {@code payment.succeeded}: Order and the Ledger both
     * subscribe, and both would refuse a synthetic payload naming an order and an intent that were
     * never created -- {@code EventDispatcher} runs handlers in sequence and an exception from one
     * aborts the pass, so Webhook would never be reached. {@code order.paid} is one of the four
     * published types and has no other consumer, so it exercises this capability and nothing else.
     */
    private OutboxEvent orderPaid(MerchantId merchantId) {
        String orderId = "ord_" + UUID.randomUUID();

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("merchantId", merchantId.value());
        payload.put("customerId", null);
        payload.put("merchantOrderReference", "ORDER-" + UUID.randomUUID());
        payload.put("amountMinor", 500000);
        payload.put("amountPaidMinor", 500000);
        payload.put("currency", "INR");
        payload.put("previousStatus", "PENDING");
        payload.put("status", "PAID");
        payload.put("occurredAt", CREATED_AT.toString());

        return new OutboxEvent(
            EventId.generate(), merchantId, "ORDER", orderId, "order.paid", 1, payload, CREATED_AT
        );
    }

    private int countEventsFor(String sourceEventId) {
        return jdbc.sql("select count(*) from webhook_events where source_event_id = ?")
            .param(sourceEventId)
            .query(Integer.class)
            .single();
    }
}
