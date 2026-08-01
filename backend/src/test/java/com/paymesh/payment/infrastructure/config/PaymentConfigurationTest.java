package com.paymesh.payment.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.order.application.PaymentActivityLookup;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class PaymentConfigurationTest {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentStateHistoryRepository paymentStateHistoryRepository;
    private final OrderLookup orderLookup;
    private final CreatePaymentIntentService createPaymentIntentService;
    private final GetPaymentIntentService getPaymentIntentService;
    private final ListPaymentIntentsService listPaymentIntentsService;
    private final AttachPaymentMethodService attachPaymentMethodService;
    private final ConfirmPaymentIntentService confirmPaymentIntentService;
    private final CancelPaymentIntentService cancelPaymentIntentService;
    private final ProviderCallbackRepository providerCallbackRepository;
    private final RecordProviderCallbackService recordProviderCallbackService;
    private final ProviderProperties providerProperties;
    private final CapturePaymentIntentService capturePaymentIntentService;
    private final TimeOutProcessingPaymentsService timeOutProcessingPaymentsService;
    private final PaymentActivityLookup paymentActivityLookup;
    private final ProcessingTimeoutProperties processingTimeoutProperties;

    @Autowired
    PaymentConfigurationTest(
        PaymentIntentRepository paymentIntentRepository,
        PaymentAttemptRepository paymentAttemptRepository,
        PaymentStateHistoryRepository paymentStateHistoryRepository,
        OrderLookup orderLookup,
        CreatePaymentIntentService createPaymentIntentService,
        GetPaymentIntentService getPaymentIntentService,
        ListPaymentIntentsService listPaymentIntentsService,
        AttachPaymentMethodService attachPaymentMethodService,
        ConfirmPaymentIntentService confirmPaymentIntentService,
        CancelPaymentIntentService cancelPaymentIntentService,
        ProviderCallbackRepository providerCallbackRepository,
        RecordProviderCallbackService recordProviderCallbackService,
        ProviderProperties providerProperties,
        CapturePaymentIntentService capturePaymentIntentService,
        TimeOutProcessingPaymentsService timeOutProcessingPaymentsService,
        PaymentActivityLookup paymentActivityLookup,
        ProcessingTimeoutProperties processingTimeoutProperties
    ) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentStateHistoryRepository = paymentStateHistoryRepository;
        this.orderLookup = orderLookup;
        this.createPaymentIntentService = createPaymentIntentService;
        this.getPaymentIntentService = getPaymentIntentService;
        this.listPaymentIntentsService = listPaymentIntentsService;
        this.attachPaymentMethodService = attachPaymentMethodService;
        this.confirmPaymentIntentService = confirmPaymentIntentService;
        this.cancelPaymentIntentService = cancelPaymentIntentService;
        this.providerCallbackRepository = providerCallbackRepository;
        this.recordProviderCallbackService = recordProviderCallbackService;
        this.providerProperties = providerProperties;
        this.capturePaymentIntentService = capturePaymentIntentService;
        this.timeOutProcessingPaymentsService = timeOutProcessingPaymentsService;
        this.paymentActivityLookup = paymentActivityLookup;
        this.processingTimeoutProperties = processingTimeoutProperties;
    }

    /**
     * Booting at all is half the assertion: ddl-auto=validate compares PaymentIntentJpaEntity,
     * PaymentStateHistoryJpaEntity and PaymentAttemptJpaEntity against the Flyway-migrated schema,
     * so a column that drifted from V8 or V9 fails here before any test body runs. That now includes
     * payment_method_type, which V8 declared and this PR is the first to map. It now also covers
     * ProviderCallbackJpaEntity against V10, and the five payment_attempts columns V9 declared and
     * the provider-callback PR is the first to map.
     * <p>
     * ProviderProperties being resolvable is part of the assertion too: it is @NotBlank, so a boot
     * that reaches this line proves the callback signing secret was actually supplied.
     * <p>
     * It also proves the two beans this capability does not own -- the TransactionTemplate and the
     * OutboxWriter, both from SharedConfiguration -- are actually reachable from Payment's wiring.
     * Creating an intent needs all three writes in one transaction, and a missing TransactionTemplate
     * would not surface until something tried.
     */
    @Test
    void providesPaymentApplicationBeans() {
        assertNotNull(createPaymentIntentService);
        assertNotNull(getPaymentIntentService);
        assertNotNull(listPaymentIntentsService);
        assertNotNull(attachPaymentMethodService);
        assertNotNull(confirmPaymentIntentService);
        assertNotNull(cancelPaymentIntentService);
        assertNotNull(recordProviderCallbackService);
        assertNotNull(capturePaymentIntentService);
        assertNotNull(timeOutProcessingPaymentsService);
        assertNotNull(providerProperties.callbackSecret());

        assertInstanceOf(JpaPaymentIntentRepository.class, paymentIntentRepository);
        assertInstanceOf(JpaPaymentAttemptRepository.class, paymentAttemptRepository);
        assertInstanceOf(JpaPaymentStateHistoryRepository.class, paymentStateHistoryRepository);
        assertInstanceOf(JpaProviderCallbackRepository.class, providerCallbackRepository);
        assertInstanceOf(OrderModuleLookup.class, orderLookup);
    }

    /**
     * THE BEAN ORDER NEEDS AND CANNOT DECLARE (ADR-014).
     * <p>
     * Payment registers an implementation of ORDER's interface, so Order's sweeper can inject it by
     * type without anything under {@code com.paymesh.order} importing Payment. Asserted from this
     * side because this is the side that owns the implementation class -- Order's own configuration
     * test deliberately asserts only that the bean resolves, so that naming
     * {@code PaymentActivityAdapter} never leaks into Order's test tree.
     */
    @Test
    void providesTheAdapterThatAnswersOrdersLiveIntentQuestion() {
        assertInstanceOf(PaymentActivityAdapter.class, paymentActivityLookup);
    }

    /**
     * THE TIMEOUT IS OFF UNDER THE dev PROFILE, which is the profile the whole suite runs under. A
     * timer failing payment intents while another test asserts on them is a flake generator.
     * application.yaml defaults it ON; application-dev.yaml is the single place that exception is
     * made, and this pins it so the line cannot be deleted silently.
     * <p>
     * The age is asserted too, and it is the money-adjacent number: it must be generous. ADR-015
     * section 4 argues why a short one is worse than a stuck order.
     */
    @Test
    void keepsTheProcessingTimeoutDisabledUnderTheProfileTheSuiteRunsUnder() {
        assertFalse(processingTimeoutProperties.enabled());
        assertTrue(processingTimeoutProperties.age().compareTo(java.time.Duration.ofMinutes(15)) >= 0);
        assertTrue(processingTimeoutProperties.batchSize() >= 1);
    }
}
