package com.paymesh.shared.outbox.infrastructure.config;

import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.application.OutboxReader;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.application.ProcessedEventRepository;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.outbox.infrastructure.persistence.jpa.JpaOutboxReader;
import com.paymesh.shared.outbox.infrastructure.persistence.jpa.JpaOutboxWriter;
import com.paymesh.shared.outbox.infrastructure.persistence.jpa.JpaProcessedEventRepository;
import com.paymesh.shared.outbox.infrastructure.persistence.jpa.SpringDataOutboxRepository;
import com.paymesh.shared.outbox.infrastructure.persistence.jpa.SpringDataProcessedEventRepository;
import com.paymesh.shared.outbox.infrastructure.schedule.OutboxRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Explicit wiring for the outbox and its relay (no component scanning of application classes).
 * <p>
 * The outbox lives in {@code shared} for the same reason the idempotency layer does: it governs
 * every capability and belongs to none. Built inside a feature package it would be platform code
 * shaped to one caller.
 * <p>
 * NOTE WHAT IS NOT NAMED HERE: any consumer. {@link EventDispatcher} takes {@code List<EventHandler>}
 * and Spring fills it from the {@code @Bean} methods each capability declares in its OWN
 * configuration -- so registering a consumer is a one-line change on the consumer's side, and this
 * file never learns that Order or the Ledger exists. That is what keeps the platform code
 * capability-agnostic while the wiring stays explicit.
 */
@Configuration
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxConfiguration {

    @Bean
    OutboxWriter outboxWriter(SpringDataOutboxRepository springDataOutboxRepository) {
        return new JpaOutboxWriter(springDataOutboxRepository);
    }

    @Bean
    OutboxReader outboxReader(SpringDataOutboxRepository springDataOutboxRepository) {
        return new JpaOutboxReader(springDataOutboxRepository);
    }

    @Bean
    ProcessedEventRepository processedEventRepository(
        SpringDataProcessedEventRepository springDataProcessedEventRepository
    ) {
        return new JpaProcessedEventRepository(springDataProcessedEventRepository);
    }

    /**
     * The TransactionTemplate is visible here on purpose: this is where a reviewer can see that
     * consuming an event takes a transaction -- the one that holds the inbox row and the handler's
     * writes together -- without opening the dispatcher.
     */
    @Bean
    EventDispatcher eventDispatcher(
        List<EventHandler> handlers,
        ProcessedEventRepository processedEventRepository,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new EventDispatcher(handlers, processedEventRepository, transactionTemplate, clock);
    }

    /**
     * The relay logic. Declared unconditionally, even when the timer below is switched off: it is an
     * ordinary object, it starts nothing on its own, and a test or an operator draining the backlog
     * by hand should not have to enable a scheduler to do it. Every integration test in this branch
     * calls {@code publish()} directly for exactly that reason.
     */
    @Bean
    PublishOutboxEventsService publishOutboxEventsService(
        OutboxReader outboxReader,
        EventDispatcher eventDispatcher,
        TransactionTemplate transactionTemplate,
        Clock clock,
        OutboxRelayProperties properties
    ) {
        return new PublishOutboxEventsService(
            outboxReader, eventDispatcher, transactionTemplate, clock, properties.batchSize()
        );
    }

    /**
     * The timer, and the only conditional bean in this file.
     * <p>
     * With the bean absent there is no {@code @Scheduled} method to register at all, so switching
     * {@code paymesh.events.outbox-relay.enabled} off genuinely stops the job rather than running a
     * no-op on every tick. It defaults ON, because a relay that is off by default is an outbox with
     * no relay, which is the state this whole change exists to end.
     * <p>
     * <b>The dev profile turns it off</b>, because that is the profile the test suite runs under and
     * a timer moving orders to PAID underneath an assertion is a flake generator. See
     * {@code application-dev.yaml} -- and note that this also means {@code ./mvnw spring-boot:run}
     * has the relay off, so exercising the Postman folder needs
     * {@code PAYMESH_EVENTS_OUTBOX_RELAY_ENABLED=true}.
     * <p>
     * {@code @EnableScheduling} is not repeated here: {@code OrderConfiguration} already declares it
     * once for the whole application, and declaring it twice registers two schedulers.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "paymesh.events.outbox-relay", name = "enabled", matchIfMissing = true
    )
    OutboxRelay outboxRelay(PublishOutboxEventsService publishOutboxEventsService) {
        return new OutboxRelay(publishOutboxEventsService);
    }
}
