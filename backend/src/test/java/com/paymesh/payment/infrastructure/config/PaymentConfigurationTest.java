package com.paymesh.payment.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.payment.application.CancelPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.payment.application.ListPaymentIntentsService;
import com.paymesh.payment.application.OrderLookup;
import com.paymesh.payment.application.PaymentIntentRepository;
import com.paymesh.payment.application.PaymentStateHistoryRepository;
import com.paymesh.payment.infrastructure.order.OrderModuleLookup;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentIntentRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentStateHistoryRepository;
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
    private final PaymentStateHistoryRepository paymentStateHistoryRepository;
    private final OrderLookup orderLookup;
    private final CreatePaymentIntentService createPaymentIntentService;
    private final GetPaymentIntentService getPaymentIntentService;
    private final ListPaymentIntentsService listPaymentIntentsService;
    private final CancelPaymentIntentService cancelPaymentIntentService;

    @Autowired
    PaymentConfigurationTest(
        PaymentIntentRepository paymentIntentRepository,
        PaymentStateHistoryRepository paymentStateHistoryRepository,
        OrderLookup orderLookup,
        CreatePaymentIntentService createPaymentIntentService,
        GetPaymentIntentService getPaymentIntentService,
        ListPaymentIntentsService listPaymentIntentsService,
        CancelPaymentIntentService cancelPaymentIntentService
    ) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentStateHistoryRepository = paymentStateHistoryRepository;
        this.orderLookup = orderLookup;
        this.createPaymentIntentService = createPaymentIntentService;
        this.getPaymentIntentService = getPaymentIntentService;
        this.listPaymentIntentsService = listPaymentIntentsService;
        this.cancelPaymentIntentService = cancelPaymentIntentService;
    }

    /**
     * Booting at all is half the assertion: ddl-auto=validate compares PaymentIntentJpaEntity and
     * PaymentStateHistoryJpaEntity against the Flyway-migrated schema, so a column that drifted from
     * V8 fails here before any test body runs.
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
        assertNotNull(cancelPaymentIntentService);

        assertInstanceOf(JpaPaymentIntentRepository.class, paymentIntentRepository);
        assertInstanceOf(JpaPaymentStateHistoryRepository.class, paymentStateHistoryRepository);
        assertInstanceOf(OrderModuleLookup.class, orderLookup);
    }
}
