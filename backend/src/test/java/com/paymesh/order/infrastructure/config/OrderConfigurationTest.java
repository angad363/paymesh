package com.paymesh.order.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.order.application.CancelOrderService;
import com.paymesh.order.application.CreateOrderService;
import com.paymesh.order.application.CustomerLookup;
import com.paymesh.order.application.GetOrderService;
import com.paymesh.order.application.ListOrdersService;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.infrastructure.customer.CustomerModuleLookup;
import com.paymesh.order.infrastructure.persistence.jpa.JpaOrderRepository;
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
class OrderConfigurationTest {

    private final OrderRepository orderRepository;
    private final CustomerLookup customerLookup;
    private final CreateOrderService createOrderService;
    private final GetOrderService getOrderService;
    private final ListOrdersService listOrdersService;
    private final CancelOrderService cancelOrderService;

    @Autowired
    OrderConfigurationTest(
        OrderRepository orderRepository,
        CustomerLookup customerLookup,
        CreateOrderService createOrderService,
        GetOrderService getOrderService,
        ListOrdersService listOrdersService,
        CancelOrderService cancelOrderService
    ) {
        this.orderRepository = orderRepository;
        this.customerLookup = customerLookup;
        this.createOrderService = createOrderService;
        this.getOrderService = getOrderService;
        this.listOrdersService = listOrdersService;
        this.cancelOrderService = cancelOrderService;
    }

    /**
     * Booting at all is half the assertion: ddl-auto=validate compares OrderJpaEntity against the
     * Flyway-migrated schema, so a column that drifted from V5 fails here before any test body runs.
     */
    @Test
    void providesOrderApplicationBeans() {
        assertNotNull(createOrderService);
        assertNotNull(getOrderService);
        assertNotNull(listOrdersService);
        assertNotNull(cancelOrderService);

        assertInstanceOf(JpaOrderRepository.class, orderRepository);
        assertInstanceOf(CustomerModuleLookup.class, customerLookup);
    }
}
