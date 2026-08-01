package com.paymesh.payment.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.CancelPaymentIntentService;
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
import com.paymesh.payment.infrastructure.order.OrderModuleLookup;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentAttemptRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentIntentRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentStateHistoryRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaProviderCallbackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        ProviderProperties providerProperties
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
        assertNotNull(providerProperties.callbackSecret());

        assertInstanceOf(JpaPaymentIntentRepository.class, paymentIntentRepository);
        assertInstanceOf(JpaPaymentAttemptRepository.class, paymentAttemptRepository);
        assertInstanceOf(JpaPaymentStateHistoryRepository.class, paymentStateHistoryRepository);
        assertInstanceOf(JpaProviderCallbackRepository.class, providerCallbackRepository);
        assertInstanceOf(OrderModuleLookup.class, orderLookup);
    }
}
