package com.paymesh.refund.infrastructure.config;

import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.refund.api.RefundCallbackController;
import com.paymesh.refund.application.CancelRefundService;
import com.paymesh.refund.application.CreateRefundService;
import com.paymesh.refund.application.GetRefundService;
import com.paymesh.refund.application.ListRefundsService;
import com.paymesh.refund.application.PaymentLookup;
import com.paymesh.refund.application.RecordRefundCallbackService;
import com.paymesh.refund.application.RefundCallbackRepository;
import com.paymesh.refund.application.RefundRepository;
import com.paymesh.refund.application.RefundStateHistoryRepository;
import com.paymesh.refund.infrastructure.payment.PaymentModuleLookup;
import com.paymesh.refund.infrastructure.persistence.jpa.JpaRefundCallbackRepository;
import com.paymesh.refund.infrastructure.persistence.jpa.JpaRefundRepository;
import com.paymesh.refund.infrastructure.persistence.jpa.JpaRefundStateHistoryRepository;
import com.paymesh.refund.infrastructure.persistence.jpa.SpringDataRefundCallbackRepository;
import com.paymesh.refund.infrastructure.persistence.jpa.SpringDataRefundRepository;
import com.paymesh.refund.infrastructure.persistence.jpa.SpringDataRefundStateHistoryRepository;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.provider.ProviderCallbackSignatureFilter;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * The Refund capability's bean wiring.
 * <p>
 * Application and domain classes carry no {@code @Service} or {@code @Autowired} -- they are plain
 * {@code final} classes instantiated here (java-coding-conventions.md 13).
 */
@Configuration
@EnableConfigurationProperties(RefundProperties.class)
public class RefundConfiguration {

    @Bean
    RefundRepository refundRepository(SpringDataRefundRepository refunds) {
        return new JpaRefundRepository(refunds);
    }

    @Bean
    RefundStateHistoryRepository refundStateHistoryRepository(
        SpringDataRefundStateHistoryRepository history
    ) {
        return new JpaRefundStateHistoryRepository(history);
    }

    @Bean
    RefundCallbackRepository refundCallbackRepository(
        SpringDataRefundCallbackRepository callbacks
    ) {
        return new JpaRefundCallbackRepository(callbacks);
    }

    /**
     * Refund's port onto Payment, wired to Payment's own read service.
     * <p>
     * This is the one bean in the module that names another capability, and it is the reason
     * {@code ModuleBoundaryTest} allowlists {@code refund/infrastructure/payment} and nothing else.
     */
    @Bean
    PaymentLookup paymentLookup(GetPaymentIntentService getPaymentIntentService) {
        return new PaymentModuleLookup(getPaymentIntentService);
    }

    @Bean
    CreateRefundService createRefundService(
        RefundRepository refundRepository,
        RefundStateHistoryRepository refundStateHistoryRepository,
        PaymentLookup paymentLookup,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CreateRefundService(
            refundRepository, refundStateHistoryRepository, paymentLookup, outboxWriter,
            transactionTemplate, clock
        );
    }

    @Bean
    GetRefundService getRefundService(RefundRepository refundRepository) {
        return new GetRefundService(refundRepository);
    }

    @Bean
    ListRefundsService listRefundsService(RefundRepository refundRepository) {
        return new ListRefundsService(refundRepository);
    }

    @Bean
    CancelRefundService cancelRefundService(
        RefundRepository refundRepository,
        RefundStateHistoryRepository refundStateHistoryRepository,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CancelRefundService(
            refundRepository, refundStateHistoryRepository, transactionTemplate, clock
        );
    }

    @Bean
    RecordRefundCallbackService recordRefundCallbackService(
        RefundRepository refundRepository,
        RefundStateHistoryRepository refundStateHistoryRepository,
        RefundCallbackRepository refundCallbackRepository,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new RecordRefundCallbackService(
            refundRepository, refundStateHistoryRepository, refundCallbackRepository,
            outboxWriter, transactionTemplate, clock
        );
    }

    /**
     * THE AUTHENTICATION FOR THE REFUND CALLBACK ROUTE, and a second instance of the one filter
     * rather than a second implementation of it (ADR-019).
     * <p>
     * A separate instance rather than widening Payment's to match both paths: they share a secret
     * today, and one filter matching both would keep working silently on the day they stop. Each
     * route names its own secret and its own payload-hash attribute here.
     * <p>
     * Ordered immediately after the security chain, like Payment's, so it runs on a request the
     * chain has already let through. Constructed inline rather than declared as its own
     * {@code @Bean} so Boot cannot also auto-register it and consume the body twice.
     */
    @Bean
    FilterRegistrationBean<ProviderCallbackSignatureFilter> refundCallbackSignatureFilterRegistration(
        RefundProperties refundProperties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        FilterRegistrationBean<ProviderCallbackSignatureFilter> registration =
            new FilterRegistrationBean<>(new ProviderCallbackSignatureFilter(
                "/internal/v1/refund-callbacks",
                refundProperties.callbackSecret(),
                RefundCallbackController.PAYLOAD_HASH_ATTRIBUTE,
                objectMapper,
                clock
            ));

        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
