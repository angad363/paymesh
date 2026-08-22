package com.paymesh.audit.infrastructure.config;

import com.paymesh.audit.application.AuditEventRepository;
import com.paymesh.audit.application.AuditExportRepository;
import com.paymesh.audit.application.GenerateAuditExportsService;
import com.paymesh.audit.application.GetAuditEventService;
import com.paymesh.audit.application.GetAuditExportService;
import com.paymesh.audit.application.ListAuditEventsService;
import com.paymesh.audit.application.RecordAuditEventService;
import com.paymesh.audit.application.RequestAuditExportService;
import com.paymesh.audit.infrastructure.AuditRecorderAdapter;
import com.paymesh.audit.infrastructure.persistence.jpa.JpaAuditEventRepository;
import com.paymesh.audit.infrastructure.persistence.jpa.JpaAuditExportRepository;
import com.paymesh.audit.infrastructure.persistence.jpa.SpringDataAuditEventRepository;
import com.paymesh.audit.infrastructure.persistence.jpa.SpringDataAuditExportRepository;
import com.paymesh.audit.infrastructure.schedule.AuditExportGenerator;
import com.paymesh.shared.audit.AuditRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Manual wiring for the Audit capability (ADR-002, java-coding-conventions section 13). Every
 * service and adapter below is a plain {@code final} class with no Spring annotation; this is the
 * only file that knows they are beans.
 *
 * <p>The {@link AuditRecorder} bean is the shared port's single implementation. Merchant, Identity
 * and Webhook receive it by that interface type in their own {@code @Bean} methods, so nothing
 * outside this module imports {@code com.paymesh.audit} -- Audit stays a leaf.
 */
@Configuration
@EnableConfigurationProperties(AuditExportProperties.class)
public class AuditConfiguration {

    @Bean
    AuditEventRepository auditEventRepository(SpringDataAuditEventRepository events) {
        return new JpaAuditEventRepository(events);
    }

    @Bean
    AuditExportRepository auditExportRepository(SpringDataAuditExportRepository exports) {
        return new JpaAuditExportRepository(exports);
    }

    @Bean
    RecordAuditEventService recordAuditEventService(AuditEventRepository events) {
        return new RecordAuditEventService(events);
    }

    @Bean
    AuditRecorder auditRecorder(RecordAuditEventService record, Clock clock) {
        return new AuditRecorderAdapter(record, clock);
    }

    @Bean
    ListAuditEventsService listAuditEventsService(AuditEventRepository events) {
        return new ListAuditEventsService(events);
    }

    @Bean
    GetAuditEventService getAuditEventService(AuditEventRepository events) {
        return new GetAuditEventService(events);
    }

    @Bean
    RequestAuditExportService requestAuditExportService(AuditExportRepository exports, Clock clock) {
        return new RequestAuditExportService(exports, clock);
    }

    @Bean
    GetAuditExportService getAuditExportService(AuditExportRepository exports) {
        return new GetAuditExportService(exports);
    }

    @Bean
    GenerateAuditExportsService generateAuditExportsService(
        AuditExportRepository exports,
        AuditEventRepository events,
        TransactionTemplate transactions,
        AuditExportProperties properties,
        Clock clock
    ) {
        return new GenerateAuditExportsService(
            exports, events, transactions, clock, properties.batchSize(), properties.maxRows()
        );
    }

    /**
     * THE TIMER, ABSENT UNDER {@code dev} like every other timer in this codebase.
     * {@code GenerateAuditExportsService} is an ordinary bean regardless, so tests call
     * {@code generate()} directly rather than waiting for a tick.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "paymesh.audit.export", name = "enabled", matchIfMissing = true
    )
    AuditExportGenerator auditExportGenerator(GenerateAuditExportsService generate) {
        return new AuditExportGenerator(generate);
    }
}
