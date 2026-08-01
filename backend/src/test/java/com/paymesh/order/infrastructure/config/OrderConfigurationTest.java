package com.paymesh.order.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.order.application.CancelOrderService;
import com.paymesh.order.application.CreateOrderService;
import com.paymesh.order.application.CustomerLookup;
import com.paymesh.order.application.ExpireOrdersService;
import com.paymesh.order.application.GetOrderService;
import com.paymesh.order.application.ListOrdersService;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.application.OrderStateHistoryRepository;
import com.paymesh.order.application.PaymentActivityLookup;
import com.paymesh.order.infrastructure.customer.CustomerModuleLookup;
import com.paymesh.order.infrastructure.persistence.jpa.JpaOrderRepository;
import com.paymesh.order.infrastructure.persistence.jpa.JpaOrderStateHistoryRepository;
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
class OrderConfigurationTest {

    private final OrderRepository orderRepository;
    private final CustomerLookup customerLookup;
    private final CreateOrderService createOrderService;
    private final GetOrderService getOrderService;
    private final ListOrdersService listOrdersService;
    private final CancelOrderService cancelOrderService;
    private final OrderStateHistoryRepository orderStateHistoryRepository;
    private final ExpireOrdersService expireOrdersService;
    private final PaymentActivityLookup paymentActivityLookup;
    private final OrderExpiryProperties orderExpiryProperties;

    @Autowired
    OrderConfigurationTest(
        OrderRepository orderRepository,
        CustomerLookup customerLookup,
        CreateOrderService createOrderService,
        GetOrderService getOrderService,
        ListOrdersService listOrdersService,
        CancelOrderService cancelOrderService,
        OrderStateHistoryRepository orderStateHistoryRepository,
        ExpireOrdersService expireOrdersService,
        PaymentActivityLookup paymentActivityLookup,
        OrderExpiryProperties orderExpiryProperties
    ) {
        this.orderRepository = orderRepository;
        this.customerLookup = customerLookup;
        this.createOrderService = createOrderService;
        this.getOrderService = getOrderService;
        this.listOrdersService = listOrdersService;
        this.cancelOrderService = cancelOrderService;
        this.orderStateHistoryRepository = orderStateHistoryRepository;
        this.expireOrdersService = expireOrdersService;
        this.paymentActivityLookup = paymentActivityLookup;
        this.orderExpiryProperties = orderExpiryProperties;
    }

    /**
     * Booting at all is half the assertion: ddl-auto=validate compares OrderJpaEntity and
     * OrderStateHistoryJpaEntity against the Flyway-migrated schema, so a column that drifted from
     * V5 or V11 fails here before any test body runs.
     */
    @Test
    void providesOrderApplicationBeans() {
        assertNotNull(createOrderService);
        assertNotNull(getOrderService);
        assertNotNull(listOrdersService);
        assertNotNull(cancelOrderService);
        assertNotNull(expireOrdersService);

        assertInstanceOf(JpaOrderRepository.class, orderRepository);
        assertInstanceOf(JpaOrderStateHistoryRepository.class, orderStateHistoryRepository);
        assertInstanceOf(CustomerModuleLookup.class, customerLookup);
    }

    /**
     * THE CROSS-MODULE BEAN, RESOLVED BY TYPE AND WIRED FROM THE OTHER SIDE (ADR-014).
     * <p>
     * Order declares {@code PaymentActivityLookup}; {@code PaymentConfiguration} registers the
     * implementation. This assertion is what proves the arrangement actually works at runtime rather
     * than merely compiling -- the sweeper takes the port as a required constructor argument, so a
     * missing implementation fails the whole context, and this test names the reason it failed.
     * <p>
     * It is deliberately NOT an {@code assertInstanceOf} against the Payment adapter class: naming
     * that type here would put an import of {@code com.paymesh.payment} in Order's own test tree,
     * which is the thing the port exists to avoid.
     */
    @Test
    void resolvesThePaymentActivityPortImplementedByTheOtherModule() {
        assertNotNull(paymentActivityLookup);
    }

    /**
     * THE SWEEPER IS OFF UNDER THE dev PROFILE, AND THIS TEST IS WHY THE SUITE IS NOT FLAKY.
     * <p>
     * Every @SpringBootTest here runs under dev. If the timer were on, it would fire during other
     * tests and expire their orders mid-assertion -- a failure that reproduces rarely and blames the
     * wrong change. application.yaml defaults it ON; application-dev.yaml is the single place that
     * exception is made, and this pins it so the line cannot be deleted silently.
     */
    @Test
    void keepsTheExpirySweeperDisabledUnderTheProfileTheSuiteRunsUnder() {
        assertFalse(orderExpiryProperties.enabled());
        assertTrue(orderExpiryProperties.batchSize() >= 1);
    }
}
