package com.paymesh.settlement.infrastructure.config;

import com.paymesh.settlement.api.PayoutCallbackController;
import com.paymesh.settlement.application.AvailableFunds;
import com.paymesh.settlement.application.CompleteSettlementService;
import com.paymesh.settlement.application.CutSettlementBatchesService;
import com.paymesh.settlement.application.GetSettlementConfigService;
import com.paymesh.settlement.application.GetSettlementsService;
import com.paymesh.settlement.application.PayoutCallbackRepository;
import com.paymesh.settlement.application.PayoutGateway;
import com.paymesh.settlement.application.PayoutRepository;
import com.paymesh.settlement.application.RecordPayoutCallbackService;
import com.paymesh.settlement.application.RevertRejectedBatchService;
import com.paymesh.settlement.application.MarkBatchPostedService;
import com.paymesh.settlement.infrastructure.events.BatchPostedHandler;
import com.paymesh.settlement.infrastructure.events.BatchRejectedHandler;
import com.paymesh.settlement.application.SettlementBatchRepository;
import com.paymesh.settlement.application.SettlementConfigRepository;
import com.paymesh.settlement.application.SubmitPayoutsService;
import com.paymesh.settlement.infrastructure.ledger.LedgerHttpAvailableFunds;
import com.paymesh.settlement.infrastructure.persistence.jpa.JpaPayoutCallbackRepository;
import com.paymesh.settlement.infrastructure.persistence.jpa.JpaPayoutRepository;
import com.paymesh.settlement.infrastructure.persistence.jpa.JpaSettlementBatchRepository;
import com.paymesh.settlement.infrastructure.persistence.jpa.JpaSettlementConfigRepository;
import com.paymesh.settlement.infrastructure.persistence.jpa.SpringDataPayoutCallbackRepository;
import com.paymesh.settlement.infrastructure.persistence.jpa.SpringDataPayoutRepository;
import com.paymesh.settlement.infrastructure.persistence.jpa.SpringDataSettlementBatchRepository;
import com.paymesh.settlement.infrastructure.persistence.jpa.SpringDataSettlementConfigRepository;
import com.paymesh.settlement.infrastructure.persistence.jpa.SpringDataSettlementItemRepository;
import com.paymesh.settlement.infrastructure.provider.HttpPayoutGateway;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.provider.ProviderCallbackSignatureFilter;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Settlement's beans, wired by hand (ADR-002).
 * <p>
 * {@code @EnableScheduling} lives here because this deployable has no monolith configuration to
 * declare it: without it neither the outbox relay nor {@link
 * com.paymesh.settlement.infrastructure.schedule.SettlementSweeper} ever fires (ADR-047; pinned by
 * {@code SettlementConfigurationTest}).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({SettlementProperties.class, PayoutProperties.class, LedgerProperties.class})
public class SettlementConfiguration {

    @Bean
    SettlementConfigRepository settlementConfigRepository(
        SpringDataSettlementConfigRepository configs
    ) {
        return new JpaSettlementConfigRepository(configs);
    }

    @Bean
    GetSettlementConfigService getSettlementConfigService(
        SettlementConfigRepository configs, SettlementProperties properties, Clock clock
    ) {
        return new GetSettlementConfigService(configs, properties.defaultHoldingPeriod(), clock);
    }

    @Bean
    SettlementBatchRepository settlementBatchRepository(
        SpringDataSettlementBatchRepository batches, SpringDataSettlementItemRepository items
    ) {
        return new JpaSettlementBatchRepository(batches, items);
    }

    @Bean
    PayoutRepository payoutRepository(SpringDataPayoutRepository payouts) {
        return new JpaPayoutRepository(payouts);
    }

    @Bean
    PayoutCallbackRepository payoutCallbackRepository(
        SpringDataPayoutCallbackRepository callbacks
    ) {
        return new JpaPayoutCallbackRepository(callbacks);
    }

    /**
     * THE ONE CROSSING INTO THE LEDGER: a read over HTTP, never a post (ADR-047 section 1a). Its own
     * {@code RestClient} with short timeouts, so a ledger that never answers costs one tick's skip
     * for one merchant rather than a hung sweep thread.
     */
    @Bean
    @ConditionalOnProperty(value = "paymesh.settlement.ledger.mode", havingValue = "http", matchIfMissing = true)
    AvailableFunds availableFunds(LedgerProperties properties, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());

        return new LedgerHttpAvailableFunds(
            RestClient.builder().requestFactory(requestFactory).build(),
            properties.url(),
            properties.key(),
            objectMapper
        );
    }

    @Bean
    GetSettlementsService getSettlementsService(SettlementBatchRepository batches) {
        return new GetSettlementsService(batches);
    }

    @Bean
    CompleteSettlementService completeSettlementService(
        SettlementBatchRepository batches,
        PayoutRepository payouts,
        OutboxWriter outboxWriter,
        Clock clock
    ) {
        return new CompleteSettlementService(batches, payouts, outboxWriter, clock);
    }

    @Bean
    CutSettlementBatchesService cutSettlementBatchesService(
        SettlementBatchRepository batches,
        PayoutRepository payouts,
        GetSettlementConfigService configs,
        AvailableFunds availableFunds,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CutSettlementBatchesService(
            batches, payouts, configs, availableFunds, outboxWriter, transactionTemplate, clock
        );
    }

    /** ADR-047 §4 option A: the Ledger refused a stale cut, so Settlement undoes its own side. */
    @Bean
    RevertRejectedBatchService revertRejectedBatchService(
        SettlementBatchRepository batches, PayoutRepository payouts, Clock clock
    ) {
        return new RevertRejectedBatchService(batches, payouts, clock);
    }

    @Bean
    BatchRejectedHandler batchRejectedHandler(RevertRejectedBatchService revert) {
        return new BatchRejectedHandler(revert);
    }

    /** ADR-047 section 8: the Ledger posted the cut, so its payout may be submitted. */
    @Bean
    MarkBatchPostedService markBatchPostedService(SettlementBatchRepository batches, Clock clock) {
        return new MarkBatchPostedService(batches, clock);
    }

    @Bean
    BatchPostedHandler batchPostedHandler(MarkBatchPostedService markPosted) {
        return new BatchPostedHandler(markPosted);
    }

    @Bean
    SubmitPayoutsService submitPayoutsService(
        PayoutRepository payouts,
        SettlementBatchRepository batches,
        CompleteSettlementService completeSettlement,
        PayoutGateway payoutGateway,
        TransactionTemplate transactionTemplate,
        SettlementProperties properties,
        Clock clock
    ) {
        return new SubmitPayoutsService(
            payouts, batches, completeSettlement, payoutGateway, transactionTemplate, clock,
            properties.cutBatchSize(), properties.payoutRetryDelay(), properties.answerTimeout()
        );
    }

    @Bean
    RecordPayoutCallbackService recordPayoutCallbackService(
        PayoutRepository payouts,
        SettlementBatchRepository batches,
        PayoutCallbackRepository callbacks,
        CompleteSettlementService completeSettlement,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new RecordPayoutCallbackService(
            payouts, batches, callbacks, completeSettlement, transactionTemplate, clock
        );
    }

    /**
     * The provider PayMesh submits payouts to, reached over HTTP like any third party.
     * <p>
     * Its own {@code RestClient} with its own short timeouts rather than a shared one: a payout
     * submission holds no lock but does hold a sweep slot, and a provider that never answers must
     * cost one slot for seconds rather than for as long as the JDK's default patience.
     */
    @Bean
    PayoutGateway payoutGateway(PayoutProperties properties, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());

        return new HttpPayoutGateway(
            RestClient.builder().requestFactory(requestFactory).build(),
            properties.url(),
            properties.apiKey(),
            objectMapper
        );
    }

    /**
     * THE AUTHENTICATION FOR THE PAYOUT CALLBACK ROUTE. A third instance of the one filter, not a
     * third implementation of it (ADR-019's rule, applied again).
     * <p>
     * Its own secret property even though dev shares one value across all three: the day payouts
     * move to a different provider, or the payment secret is rotated after a leak, this one changes
     * on its own. Constructed inline rather than as a {@code @Bean} so Boot cannot also
     * auto-register it and consume the body twice.
     */
    @Bean
    FilterRegistrationBean<ProviderCallbackSignatureFilter> payoutCallbackSignatureFilterRegistration(
        PayoutProperties properties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        FilterRegistrationBean<ProviderCallbackSignatureFilter> registration =
            new FilterRegistrationBean<>(new ProviderCallbackSignatureFilter(
                "/internal/v1/payout-callbacks",
                properties.callbackSecret(),
                PayoutCallbackController.PAYLOAD_HASH_ATTRIBUTE,
                objectMapper,
                clock
            ));

        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);

        return registration;
    }
}
