package com.paymesh.ledger.infrastructure.events;

import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * Feeds {@code settlement_config_ref} from {@code settlement.config.updated} (ADR-051). Runs in the
 * dispatcher's transaction (no transaction of its own) and is idempotent via the upsert. A payload
 * with no period is a producer bug, not a transient fault, so it throws rather than projecting
 * garbage: on the in-process path the dispatcher's transaction rolls back and the event is retried;
 * on the Kafka path {@code KafkaEventListener}'s bounded retry runs and then advances the offset
 * (there is no dead-letter topic until a capability is Kafka-only), so a persistently bad payload is
 * logged and skipped rather than blocking the partition. The merchant comes from the ENVELOPE, not
 * the payload.
 */
public final class SettlementConfigRefProjector implements EventHandler {

    private final SettlementConfigRefStore store;

    public SettlementConfigRefProjector(SettlementConfigRefStore store) {
        this.store = store;
    }

    /** STABLE: renaming it re-opens the whole backlog to this consumer. */
    @Override
    public String consumerName() {
        return "ledger.settlement-config";
    }

    @Override
    public String eventType() {
        return "settlement.config.updated";
    }

    @Override
    public void handle(OutboxEvent event) {
        if (!(event.payload().get("holdingPeriodSeconds") instanceof Number seconds)
            || event.merchantId() == null) {
            throw new IllegalStateException(
                "settlement.config.updated (" + event.eventId().value()
                    + ") carries no merchant or holdingPeriodSeconds; cannot project"
            );
        }

        store.upsert(event.merchantId(), seconds.longValue(), event.occurredAt());
    }
}
