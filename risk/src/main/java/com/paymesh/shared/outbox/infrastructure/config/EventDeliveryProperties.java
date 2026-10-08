package com.paymesh.shared.outbox.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which sinks the relay delivers to (ADR-037), and the one switch the dual path hangs off.
 * <p>
 * It governs the producer (whether {@link com.paymesh.shared.outbox.application.PublishOutboxEventsService}
 * also publishes to Kafka) and the consumer (whether the Kafka listener bean is registered) from a
 * single property, so the two halves can never disagree about whether Kafka is in play.
 * <p>
 * A third {@code kafka}-only mode is deliberately absent (ADR-037 §1): it is where a capability lands
 * once it is extracted, and a mode with no caller in this deployable is configuration debt.
 *
 * @param mode {@link DeliveryMode#BOTH} by default (the whole point of the PR) and
 *             {@link DeliveryMode#IN_PROCESS} under the {@code dev} profile and as the rollback. A
 *             missing value is treated as {@code BOTH}, matching the {@code matchIfMissing} on the
 *             listener's {@code @ConditionalOnProperty} so producer and consumer stay in step.
 */
@ConfigurationProperties("paymesh.events.delivery")
public record EventDeliveryProperties(DeliveryMode mode) {

    public EventDeliveryProperties {
        if (mode == null) {
            mode = DeliveryMode.BOTH;
        }
    }

    /** True when the relay should also put each event on the broker. */
    public boolean publishesToKafka() {
        return mode == DeliveryMode.BOTH;
    }

    public enum DeliveryMode {

        /** In-process dispatch only. The pre-ADR-036 behaviour, the {@code dev} default, the rollback. */
        IN_PROCESS,

        /** In-process dispatch AND Kafka, deduped by the same inbox. The default. */
        BOTH
    }
}
