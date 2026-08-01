package com.paymesh.payment.infrastructure.config;

import com.paymesh.order.application.GetOrderService;
import com.paymesh.order.application.PaymentActivityLookup;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.CancelAbandonedPaymentIntentsService;
import com.paymesh.payment.application.CancelPaymentIntentService;
import com.paymesh.payment.application.CapturePaymentIntentService;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.payment.application.ListPaymentIntentsService;
import com.paymesh.payment.application.OrderLookup;
import com.paymesh.payment.application.PaymentAttemptRepository;
import com.paymesh.payment.application.PaymentIntentRepository;
import com.paymesh.payment.application.PaymentStateHistoryRepository;
import com.paymesh.payment.application.ProviderCallbackRepository;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.application.TimeOutProcessingPaymentsService;
import com.paymesh.payment.infrastructure.order.OrderModuleLookup;
import com.paymesh.payment.infrastructure.order.PaymentActivityAdapter;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentAttemptRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentIntentRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentStateHistoryRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaProviderCallbackRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.SpringDataPaymentAttemptRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.SpringDataPaymentIntentRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.SpringDataPaymentStateHistoryRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.SpringDataProviderCallbackRepository;
import com.paymesh.payment.infrastructure.provider.ProviderCallbackSignatureFilter;
import com.paymesh.payment.infrastructure.schedule.AbandonedIntentSweeper;
import com.paymesh.payment.infrastructure.schedule.ProcessingTimeoutSweeper;
import com.paymesh.shared.outbox.application.OutboxWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Explicit wiring for the payment capability (no component scanning of application/domain classes).
 * The Clock, the OutboxWriter and the TransactionTemplate are injected, not declared:
 * SharedConfiguration owns all three, because they are needed by every capability and belong to
 * none of them.
 * <p>
 * This class is also the only place in the module besides the adapter itself where Order's services
 * are named. That is the point of the OrderLookup port (ADR-008): the dependency is visible in one
 * file instead of spread through the application layer.
 */
@Configuration
@EnableConfigurationProperties({
    ProviderProperties.class,
    ProcessingTimeoutProperties.class,
    AbandonedIntentProperties.class
})
public class PaymentConfiguration {

    @Bean
    PaymentIntentRepository paymentIntentRepository(
        SpringDataPaymentIntentRepository springDataPaymentIntentRepository
    ) {
        return new JpaPaymentIntentRepository(springDataPaymentIntentRepository);
    }

    @Bean
    PaymentStateHistoryRepository paymentStateHistoryRepository(
        SpringDataPaymentStateHistoryRepository springDataPaymentStateHistoryRepository
    ) {
        return new JpaPaymentStateHistoryRepository(springDataPaymentStateHistoryRepository);
    }

    @Bean
    OrderLookup orderLookup(GetOrderService getOrderService) {
        return new OrderModuleLookup(getOrderService);
    }

    @Bean
    CreatePaymentIntentService createPaymentIntentService(
        PaymentIntentRepository paymentIntentRepository,
        PaymentStateHistoryRepository paymentStateHistoryRepository,
        OrderLookup orderLookup,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CreatePaymentIntentService(
            paymentIntentRepository,
            paymentStateHistoryRepository,
            orderLookup,
            outboxWriter,
            transactionTemplate,
            clock
        );
    }

    @Bean
    GetPaymentIntentService getPaymentIntentService(PaymentIntentRepository paymentIntentRepository) {
        return new GetPaymentIntentService(paymentIntentRepository);
    }

    @Bean
    ListPaymentIntentsService listPaymentIntentsService(
        PaymentIntentRepository paymentIntentRepository
    ) {
        return new ListPaymentIntentsService(paymentIntentRepository);
    }

    @Bean
    PaymentAttemptRepository paymentAttemptRepository(
        SpringDataPaymentAttemptRepository springDataPaymentAttemptRepository
    ) {
        return new JpaPaymentAttemptRepository(springDataPaymentAttemptRepository);
    }

    @Bean
    AttachPaymentMethodService attachPaymentMethodService(
        PaymentIntentRepository paymentIntentRepository,
        PaymentStateHistoryRepository paymentStateHistoryRepository,
        GetPaymentIntentService getPaymentIntentService,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new AttachPaymentMethodService(
            paymentIntentRepository,
            paymentStateHistoryRepository,
            getPaymentIntentService,
            outboxWriter,
            transactionTemplate,
            clock
        );
    }

    @Bean
    ConfirmPaymentIntentService confirmPaymentIntentService(
        PaymentIntentRepository paymentIntentRepository,
        PaymentAttemptRepository paymentAttemptRepository,
        PaymentStateHistoryRepository paymentStateHistoryRepository,
        GetPaymentIntentService getPaymentIntentService,
        OrderLookup orderLookup,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new ConfirmPaymentIntentService(
            paymentIntentRepository,
            paymentAttemptRepository,
            paymentStateHistoryRepository,
            getPaymentIntentService,
            orderLookup,
            outboxWriter,
            transactionTemplate,
            clock
        );
    }

    @Bean
    ProviderCallbackRepository providerCallbackRepository(
        SpringDataProviderCallbackRepository springDataProviderCallbackRepository
    ) {
        return new JpaProviderCallbackRepository(springDataProviderCallbackRepository);
    }

    @Bean
    RecordProviderCallbackService recordProviderCallbackService(
        PaymentIntentRepository paymentIntentRepository,
        PaymentAttemptRepository paymentAttemptRepository,
        PaymentStateHistoryRepository paymentStateHistoryRepository,
        ProviderCallbackRepository providerCallbackRepository,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new RecordProviderCallbackService(
            paymentIntentRepository,
            paymentAttemptRepository,
            paymentStateHistoryRepository,
            providerCallbackRepository,
            outboxWriter,
            transactionTemplate,
            clock
        );
    }

    /**
     * The signature check, in front of the callback controller.
     * <p>
     * Ordered after Spring Security's chain, like the idempotency filter, and for a related reason:
     * the security chain is what declares the route {@code permitAll()}, and a filter running before
     * it would be verifying signatures on requests the chain might still reject. It only inspects
     * {@code /internal/v1/provider-callbacks/**} -- see the filter's {@code shouldNotFilter}.
     * <p>
     * Constructed here rather than declared as its own {@code @Bean} so Boot cannot also
     * auto-register it and run it twice, which would consume the request body before the controller.
     */
    @Bean
    FilterRegistrationBean<ProviderCallbackSignatureFilter> providerCallbackSignatureFilterRegistration(
        ProviderProperties providerProperties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        FilterRegistrationBean<ProviderCallbackSignatureFilter> registration =
            new FilterRegistrationBean<>(new ProviderCallbackSignatureFilter(
                providerProperties.callbackSecret(), objectMapper, clock
            ));

        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }

    /**
     * MANUAL capture: AUTHORIZED to SUCCEEDED, at the merchant's request.
     * <p>
     * It takes the {@code OrderLookup} port, and that is not copy-paste from confirm. Capture is
     * where the funds are actually taken on a MANUAL intent, so it is the transition ADR-013's
     * payability re-read has to be on -- see the service's {@code requireStillPayable}.
     */
    @Bean
    CapturePaymentIntentService capturePaymentIntentService(
        PaymentIntentRepository paymentIntentRepository,
        PaymentStateHistoryRepository paymentStateHistoryRepository,
        GetPaymentIntentService getPaymentIntentService,
        OrderLookup orderLookup,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CapturePaymentIntentService(
            paymentIntentRepository,
            paymentStateHistoryRepository,
            getPaymentIntentService,
            orderLookup,
            outboxWriter,
            transactionTemplate,
            clock
        );
    }

    /**
     * THE BEAN THAT ANSWERS ORDER'S QUESTION, DECLARED ON THIS SIDE OF THE BOUNDARY (ADR-014).
     * <p>
     * The bean type is {@code com.paymesh.order.application.PaymentActivityLookup} -- Order's own
     * interface -- so Order's sweeper can inject it by type without {@code OrderConfiguration} or
     * anything else under {@code com.paymesh.order} naming a Payment class. Payment already imports
     * Order; Order still imports nothing of Payment, and
     * {@code ModuleBoundaryTest.orderNeverImportsPayment} keeps its empty allowlist.
     * <p>
     * Required, not optional. If Payment is extracted and nothing replaces this, the context fails
     * to start -- correct, because a sweeper that cannot ask whether an order is being paid must not
     * run, and a silently absent implementation would expire live orders in production while every
     * test in the Order module stayed green.
     */
    @Bean
    PaymentActivityLookup paymentActivityLookup(PaymentIntentRepository paymentIntentRepository) {
        return new PaymentActivityAdapter(paymentIntentRepository);
    }

    /**
     * The PROCESSING timeout's logic. Declared unconditionally, even when the timer below is off: it
     * is an ordinary object, it starts nothing, and a test or an operator running a single sweep by
     * hand should not have to enable a scheduler to do it.
     */
    @Bean
    TimeOutProcessingPaymentsService timeOutProcessingPaymentsService(
        PaymentIntentRepository paymentIntentRepository,
        PaymentStateHistoryRepository paymentStateHistoryRepository,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock,
        ProcessingTimeoutProperties properties
    ) {
        return new TimeOutProcessingPaymentsService(
            paymentIntentRepository,
            paymentStateHistoryRepository,
            outboxWriter,
            transactionTemplate,
            clock,
            properties.age(),
            properties.batchSize()
        );
    }

    /**
     * The timer. With the bean absent there is no {@code @Scheduled} method to register, so switching
     * {@code paymesh.payments.processing-timeout.enabled} off genuinely stops the job. It defaults
     * ON, because the hole it closes is the largest known one in the design; the dev profile turns it
     * off because that is the profile the test suite runs under.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "paymesh.payments.processing-timeout", name = "enabled", matchIfMissing = true
    )
    ProcessingTimeoutSweeper processingTimeoutSweeper(
        TimeOutProcessingPaymentsService timeOutProcessingPaymentsService
    ) {
        return new ProcessingTimeoutSweeper(timeOutProcessingPaymentsService);
    }

    /**
     * Releases the order slot an abandoned checkout is holding.
     * <p>
     * Declared separately from the PROCESSING timeout on purpose. The two sweeps rhyme and their
     * safety arguments are opposites -- one can record a real payment as failed, the other cannot
     * touch a payment that was ever sent anywhere -- so they get their own ages, their own switches
     * and their own beans. A single knob would apply one argument's reasoning to the other.
     */
    @Bean
    CancelAbandonedPaymentIntentsService cancelAbandonedPaymentIntentsService(
        PaymentIntentRepository paymentIntentRepository,
        CancelPaymentIntentService cancelPaymentIntentService,
        Clock clock,
        AbandonedIntentProperties properties
    ) {
        return new CancelAbandonedPaymentIntentsService(
            paymentIntentRepository,
            cancelPaymentIntentService,
            clock,
            properties.age(),
            properties.batchSize()
        );
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "paymesh.payments.abandoned-intents", name = "enabled", matchIfMissing = true
    )
    AbandonedIntentSweeper abandonedIntentSweeper(
        CancelAbandonedPaymentIntentsService cancelAbandonedPaymentIntentsService
    ) {
        return new AbandonedIntentSweeper(cancelAbandonedPaymentIntentsService);
    }

    @Bean
    CancelPaymentIntentService cancelPaymentIntentService(
        PaymentIntentRepository paymentIntentRepository,
        PaymentStateHistoryRepository paymentStateHistoryRepository,
        GetPaymentIntentService getPaymentIntentService,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CancelPaymentIntentService(
            paymentIntentRepository,
            paymentStateHistoryRepository,
            getPaymentIntentService,
            outboxWriter,
            transactionTemplate,
            clock
        );
    }
}
