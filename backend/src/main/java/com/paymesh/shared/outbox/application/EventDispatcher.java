package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hands one event to every consumer subscribed to its type, exactly once each (ADR-016).
 *
 * <h2>In-process and synchronous, and the contract is still the broker's</h2>
 *
 * There is no Kafka here and no thread pool. ADR-001 says extract services only after the contracts
 * are proven, and a broker between two packages in one JVM buys nothing but operational surface.
 * What this class does buy is the SHAPE: {@link EventHandler} receives an envelope, is deduplicated
 * through {@code processed_events}, and is required to be idempotent -- so replacing this class with
 * a Kafka listener changes no consumer.
 *
 * <h2>ONE TRANSACTION PER (HANDLER, EVENT), AND THAT IS THE WHOLE POINT OF THE INBOX</h2>
 *
 * The inbox row and everything the handler writes commit together, so a duplicate delivery finds the
 * row and does nothing, and a failed handler leaves no row and is retried. If those two could commit
 * separately the pattern would be pointless -- V14's header spells out what each way of splitting
 * them loses.
 * <p>
 * <b>Per handler, not per event.</b> With one transaction spanning every consumer, one of them
 * failing would roll back the others' committed work and all of them would re-apply; the inbox would
 * record nothing, because it rolled back too. Per handler, a Ledger failure leaves Order processed
 * and retries only the Ledger. Consumers of one event are independent by construction, which is the
 * property that lets them be separate services later.
 *
 * <h2>An event nobody handles is not an error</h2>
 *
 * {@code order.created}, {@code payment.created} and {@code payment.cancelled} have no consumer. They
 * dispatch to an empty list, the relay stamps them published, and they are done. {@code published_at}
 * means "delivered to everyone subscribed", and for zero subscribers that is immediate.
 */
public final class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final Map<String, List<EventHandler>> handlersByType;
    private final ProcessedEventRepository processedEvents;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * @param handlers every registered consumer, injected as a list. Spring collects them from the
     *                 {@code @Bean} methods each capability declares in its OWN configuration, so
     *                 registration is explicit and visible on the consumer's side -- and this class
     *                 never names a capability.
     * @throws IllegalArgumentException when two handlers claim the same consumer name for the same
     *                                  event type. That is not a configuration to tolerate: the two
     *                                  would share an inbox row, so one of them would silently never
     *                                  run.
     */
    public EventDispatcher(
        List<EventHandler> handlers,
        ProcessedEventRepository processedEvents,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.handlersByType = index(handlers);
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
    }

    private static Map<String, List<EventHandler>> index(List<EventHandler> handlers) {
        Map<String, List<EventHandler>> byType = new LinkedHashMap<>();

        for (EventHandler handler : handlers) {
            List<EventHandler> forType =
                byType.computeIfAbsent(handler.eventType(), type -> new ArrayList<>());

            boolean duplicate = forType.stream()
                .anyMatch(existing -> existing.consumerName().equals(handler.consumerName()));

            if (duplicate) {
                throw new IllegalArgumentException(
                    "Two handlers of " + handler.eventType() + " both call themselves "
                        + handler.consumerName() + "; they would share one inbox row"
                );
            }

            forType.add(handler);
        }

        return byType;
    }

    /**
     * Delivers one event.
     * <p>
     * Exceptions PROPAGATE. The relay catches them, counts them, and leaves {@code published_at}
     * null so the event is redelivered -- swallowing here would tell the relay a failed delivery
     * succeeded, which is the one thing that loses an event permanently.
     */
    public void dispatch(OutboxEvent event) {
        for (EventHandler handler : handlersByType.getOrDefault(event.eventType(), List.of())) {
            transactions.execute(status -> {
                // THE CLAIM, AND IT IS A WRITE RATHER THAN A READ. Two concurrent deliveries of one
                // event both reach here; the second blocks on the primary-key index entry until the
                // first commits, then reads zero rows inserted and correctly does nothing. There is
                // no window between deciding and acting, because the decision IS the act.
                boolean claimed = processedEvents.markProcessed(
                    handler.consumerName(), event.eventId(), event.eventType(), Instant.now(clock)
                );

                if (!claimed) {
                    // The debug level is deliberate: at-least-once means duplicates are NORMAL, not
                    // exceptional, and a WARN per redelivery would train an operator to ignore warnings.
                    log.debug(
                        "Event already applied, skipping consumer={} eventId={} eventType={}",
                        handler.consumerName(), event.eventId().value(), event.eventType()
                    );

                    return null;
                }

                handler.handle(event);

                return null;
            });
        }
    }
}
