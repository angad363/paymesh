package com.paymesh.settlement.infrastructure.config;

import com.paymesh.ledger.application.BalanceRepository;
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
import com.paymesh.settlement.application.SettlementBatchRepository;
import com.paymesh.settlement.application.SettlementConfigRepository;
import com.paymesh.settlement.application.SubmitPayoutsService;
import com.paymesh.settlement.infrastructure.ledger.LedgerModuleAvailableFunds;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** Settlement's beans, wired by hand (ADR-002). */
@Configuration
@EnableConfigurationProperties({SettlementProperties.class, PayoutProperties.class})
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
     * THE ONE CROSSING INTO THE LEDGER, and the return half of a pair
     * {@code ModuleBoundaryTest} names file by file. See {@link LedgerModuleAvailableFunds} for why
     * a cycle between these two modules is allowed here and nowhere else.
     */
    @Bean
    AvailableFunds availableFunds(BalanceRepository balanceRepository) {
        return new LedgerModuleAvailableFunds(balanceRepository);
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
