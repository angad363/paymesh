package com.paymesh.webhook.infrastructure.config;

import com.paymesh.webhook.application.DeliverWebhooksService;
import com.paymesh.webhook.application.FanOutWebhookEventService;
import com.paymesh.webhook.application.ListWebhookDeliveriesService;
import com.paymesh.webhook.application.RegisterWebhookEndpointService;
import com.paymesh.webhook.application.ReplayWebhookDeliveryService;
import com.paymesh.webhook.application.RotateWebhookSecretService;
import com.paymesh.webhook.application.UpdateWebhookEndpointService;
import com.paymesh.webhook.application.WebhookDeliveryRepository;
import com.paymesh.webhook.application.WebhookEndpointRepository;
import com.paymesh.webhook.application.WebhookEventRepository;
import com.paymesh.webhook.application.WebhookSender;
import com.paymesh.webhook.infrastructure.events.WebhookFanOutHandler;
import com.paymesh.webhook.infrastructure.events.WebhookPayloadTranslator;
import com.paymesh.webhook.infrastructure.http.HttpWebhookSender;
import com.paymesh.webhook.infrastructure.persistence.jpa.JpaWebhookDeliveryRepository;
import com.paymesh.webhook.infrastructure.persistence.jpa.JpaWebhookEndpointRepository;
import com.paymesh.webhook.infrastructure.persistence.jpa.JpaWebhookEventRepository;
import com.paymesh.webhook.infrastructure.persistence.jpa.SpringDataWebhookDeliveryRepository;
import com.paymesh.webhook.infrastructure.persistence.jpa.SpringDataWebhookEndpointRepository;
import com.paymesh.webhook.infrastructure.persistence.jpa.SpringDataWebhookEventRepository;
import com.paymesh.webhook.infrastructure.schedule.WebhookDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Manual wiring for the Webhook capability (ADR-002, java-coding-conventions §13). Every service and
 * adapter below is a plain {@code final} class with no Spring annotation; this is the only file that
 * knows they are beans.
 */
@Configuration
@EnableConfigurationProperties({WebhookProperties.class, WebhookDispatchProperties.class})
public class WebhookConfiguration {

    private static final String DEV_PROFILE = "dev";

    @Bean
    WebhookEndpointRepository webhookEndpointRepository(
        SpringDataWebhookEndpointRepository endpoints
    ) {
        return new JpaWebhookEndpointRepository(endpoints);
    }

    @Bean
    WebhookEventRepository webhookEventRepository(SpringDataWebhookEventRepository events) {
        return new JpaWebhookEventRepository(events);
    }

    @Bean
    WebhookDeliveryRepository webhookDeliveryRepository(
        SpringDataWebhookDeliveryRepository deliveries
    ) {
        return new JpaWebhookDeliveryRepository(deliveries);
    }

    @Bean
    WebhookPayloadTranslator webhookPayloadTranslator(ObjectMapper objectMapper) {
        return new WebhookPayloadTranslator(objectMapper);
    }

    @Bean
    FanOutWebhookEventService fanOutWebhookEventService(
        WebhookEndpointRepository endpoints,
        WebhookEventRepository events,
        WebhookDeliveryRepository deliveries,
        Clock clock
    ) {
        return new FanOutWebhookEventService(endpoints, events, deliveries, clock);
    }

    /**
     * FOUR HANDLER BEANS, ONE CLASS. {@code EventDispatcher} collects every {@link
     * com.paymesh.shared.outbox.application.EventHandler} bean in the context and indexes it by
     * type, so subscribing to a fifth event is a line here plus a translator -- and the handler's
     * constructor refuses a type the translator does not know, which makes the pairing a startup
     * failure rather than a per-event one.
     */
    @Bean
    WebhookFanOutHandler paymentSucceededWebhookHandler(
        WebhookPayloadTranslator translator, FanOutWebhookEventService fanOut
    ) {
        return new WebhookFanOutHandler("payment.succeeded", translator, fanOut);
    }

    @Bean
    WebhookFanOutHandler paymentFailedWebhookHandler(
        WebhookPayloadTranslator translator, FanOutWebhookEventService fanOut
    ) {
        return new WebhookFanOutHandler("payment.failed", translator, fanOut);
    }

    @Bean
    WebhookFanOutHandler refundSucceededWebhookHandler(
        WebhookPayloadTranslator translator, FanOutWebhookEventService fanOut
    ) {
        return new WebhookFanOutHandler("refund.succeeded", translator, fanOut);
    }

    @Bean
    WebhookFanOutHandler orderPaidWebhookHandler(
        WebhookPayloadTranslator translator, FanOutWebhookEventService fanOut
    ) {
        return new WebhookFanOutHandler("order.paid", translator, fanOut);
    }

    /**
     * The outbound client, and the two settings on it that are security controls rather than tuning.
     *
     * <h2>{@code allowPrivateAddresses} IS HONOURED ONLY UNDER {@code dev}, AND ONLY ALONE</h2>
     *
     * It disables the SSRF guard, so a deployment that set it by accident would let any merchant
     * point PayMesh at the instance metadata service. The profile rule is
     * {@code DevelopmentSecretGuard}'s: {@code dev} must be the ONLY active profile, because layered
     * configuration appends profiles rather than replacing them and {@code dev,production} is an
     * ordinary operator slip.
     */
    @Bean
    WebhookSender webhookSender(
        WebhookProperties properties,
        WebhookDispatchProperties dispatchProperties,
        Environment environment,
        Clock clock
    ) {
        return HttpWebhookSender.create(
            properties.masterKey(),
            properties.allowPrivateAddresses() && isDevelopmentOnly(environment),
            dispatchProperties.connectTimeout(),
            dispatchProperties.readTimeout(),
            clock
        );
    }

    @Bean
    DeliverWebhooksService deliverWebhooksService(
        WebhookDeliveryRepository deliveries,
        WebhookEndpointRepository endpoints,
        WebhookEventRepository events,
        WebhookSender sender,
        TransactionTemplate transactions,
        WebhookDispatchProperties dispatchProperties,
        Clock clock
    ) {
        return new DeliverWebhooksService(
            deliveries, endpoints, events, sender, transactions, clock,
            dispatchProperties.batchSize()
        );
    }

    @Bean
    RegisterWebhookEndpointService registerWebhookEndpointService(
        WebhookEndpointRepository endpoints,
        WebhookProperties properties,
        TransactionTemplate transactions,
        Clock clock
    ) {
        return new RegisterWebhookEndpointService(
            endpoints,
            WebhookPayloadTranslator.PUBLISHED_TYPES,
            properties.masterKey().getBytes(StandardCharsets.UTF_8),
            transactions,
            clock
        );
    }

    @Bean
    UpdateWebhookEndpointService updateWebhookEndpointService(
        WebhookEndpointRepository endpoints, TransactionTemplate transactions, Clock clock
    ) {
        return new UpdateWebhookEndpointService(
            endpoints, WebhookPayloadTranslator.PUBLISHED_TYPES, transactions, clock
        );
    }

    @Bean
    RotateWebhookSecretService rotateWebhookSecretService(
        WebhookEndpointRepository endpoints,
        WebhookProperties properties,
        TransactionTemplate transactions,
        Clock clock
    ) {
        return new RotateWebhookSecretService(
            endpoints, properties.masterKey().getBytes(StandardCharsets.UTF_8), transactions, clock
        );
    }

    @Bean
    ListWebhookDeliveriesService listWebhookDeliveriesService(
        WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints
    ) {
        return new ListWebhookDeliveriesService(deliveries, endpoints);
    }

    @Bean
    ReplayWebhookDeliveryService replayWebhookDeliveryService(
        WebhookDeliveryRepository deliveries, TransactionTemplate transactions, Clock clock
    ) {
        return new ReplayWebhookDeliveryService(deliveries, transactions, clock);
    }

    /**
     * THE TIMER, AND IT IS ABSENT UNDER {@code dev}, like every other timer in this codebase.
     * {@code DeliverWebhooksService} is an ordinary bean regardless, so tests call {@code dispatch()}
     * directly rather than waiting for a tick.
     */
    @Bean
    @ConditionalOnProperty(prefix = "paymesh.webhook.dispatch", name = "enabled", matchIfMissing = true)
    WebhookDispatcher webhookDispatcher(DeliverWebhooksService deliverWebhooks) {
        return new WebhookDispatcher(deliverWebhooks);
    }

    private static boolean isDevelopmentOnly(Environment environment) {
        String[] active = environment.getActiveProfiles();

        return active.length == 1 && DEV_PROFILE.equals(active[0]);
    }
}
