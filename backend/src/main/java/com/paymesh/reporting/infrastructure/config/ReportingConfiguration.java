package com.paymesh.reporting.infrastructure.config;

import com.paymesh.reporting.application.GenerateReportExportsService;
import com.paymesh.reporting.application.GetReportExportService;
import com.paymesh.reporting.application.GetReportsService;
import com.paymesh.reporting.application.RecordReportFactService;
import com.paymesh.reporting.application.ReportExportRepository;
import com.paymesh.reporting.application.ReportFactRepository;
import com.paymesh.reporting.application.RequestReportExportService;
import com.paymesh.reporting.infrastructure.events.ReportFactHandler;
import com.paymesh.reporting.infrastructure.persistence.jpa.JpaReportExportRepository;
import com.paymesh.reporting.infrastructure.persistence.jpa.JpaReportFactRepository;
import com.paymesh.reporting.infrastructure.persistence.jpa.SpringDataReportExportRepository;
import com.paymesh.reporting.infrastructure.persistence.jpa.SpringDataReportFactRepository;
import com.paymesh.reporting.infrastructure.schedule.ReportExportGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Manual wiring for the Reporting capability (ADR-002, java-coding-conventions section 13). Every
 * service and adapter below is a plain {@code final} class with no Spring annotation; this is the
 * only file that knows they are beans.
 */
@Configuration
@EnableConfigurationProperties(ReportExportProperties.class)
public class ReportingConfiguration {

    @Bean
    ReportFactRepository reportFactRepository(SpringDataReportFactRepository facts) {
        return new JpaReportFactRepository(facts);
    }

    @Bean
    ReportExportRepository reportExportRepository(SpringDataReportExportRepository exports) {
        return new JpaReportExportRepository(exports);
    }

    @Bean
    RecordReportFactService recordReportFactService(ReportFactRepository facts, Clock clock) {
        return new RecordReportFactService(facts, clock);
    }

    @Bean
    GetReportsService getReportsService(ReportFactRepository facts) {
        return new GetReportsService(facts);
    }

    @Bean
    RequestReportExportService requestReportExportService(
        ReportExportRepository exports, Clock clock
    ) {
        return new RequestReportExportService(exports, clock);
    }

    @Bean
    GetReportExportService getReportExportService(ReportExportRepository exports) {
        return new GetReportExportService(exports);
    }

    @Bean
    GenerateReportExportsService generateReportExportsService(
        ReportExportRepository exports,
        ReportFactRepository facts,
        TransactionTemplate transactions,
        ReportExportProperties properties,
        Clock clock
    ) {
        return new GenerateReportExportsService(
            exports, facts, transactions, clock, properties.batchSize(), properties.maxRows()
        );
    }

    /**
     * SIX HANDLER BEANS, ONE CLASS. {@code EventDispatcher} collects every {@code EventHandler} bean
     * and indexes it by type, so subscribing to a seventh event is a line here, an extraction in
     * {@code ReportFactExtractor}, an entry in {@code ReportFact.SUBSCRIBED_TYPES} and a widened
     * {@code ck_report_facts_event_type}. The handler's constructor refuses a type the extractor
     * does not know, which makes three of those four a startup failure rather than a per-event one;
     * {@code ReportingConfigurationTest} covers the fourth.
     *
     * <p>{@code order.paid} is deliberately absent. It is Order's restatement of
     * {@code payment.succeeded}, so subscribing to both would count every collection twice -- the
     * same reason Notification omits it.
     */
    @Bean
    ReportFactHandler paymentSucceededReportHandler(RecordReportFactService record) {
        return new ReportFactHandler("payment.succeeded", record);
    }

    @Bean
    ReportFactHandler paymentFailedReportHandler(RecordReportFactService record) {
        return new ReportFactHandler("payment.failed", record);
    }

    @Bean
    ReportFactHandler refundSucceededReportHandler(RecordReportFactService record) {
        return new ReportFactHandler("refund.succeeded", record);
    }

    @Bean
    ReportFactHandler settlementBatchCutReportHandler(RecordReportFactService record) {
        return new ReportFactHandler("settlement.batch_cut", record);
    }

    @Bean
    ReportFactHandler payoutPaidReportHandler(RecordReportFactService record) {
        return new ReportFactHandler("payout.paid", record);
    }

    @Bean
    ReportFactHandler payoutReturnedReportHandler(RecordReportFactService record) {
        return new ReportFactHandler("payout.returned", record);
    }

    /**
     * THE TIMER, ABSENT UNDER {@code dev} like every other timer in this codebase.
     * {@code GenerateReportExportsService} is an ordinary bean regardless, so tests call
     * {@code generate()} directly rather than waiting for a tick.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "paymesh.reporting.export", name = "enabled", matchIfMissing = true
    )
    ReportExportGenerator reportExportGenerator(GenerateReportExportsService generate) {
        return new ReportExportGenerator(generate);
    }
}
