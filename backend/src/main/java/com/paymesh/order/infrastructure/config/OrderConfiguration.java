package com.paymesh.order.infrastructure.config;

import com.paymesh.customer.application.GetCustomerService;
import com.paymesh.order.application.CancelOrderService;
import com.paymesh.order.application.CreateOrderService;
import com.paymesh.order.application.CustomerLookup;
import com.paymesh.order.application.GetOrderService;
import com.paymesh.order.application.ListOrdersService;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.infrastructure.customer.CustomerModuleLookup;
import com.paymesh.order.infrastructure.persistence.jpa.JpaOrderRepository;
import com.paymesh.order.infrastructure.persistence.jpa.SpringDataOrderRepository;
import com.paymesh.shared.outbox.application.OutboxWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Explicit wiring for the order capability (no component scanning of application/domain classes).
 * The Clock is injected, not declared: SharedConfiguration owns it, because time is needed by every
 * capability and belongs to none of them.
 * <p>
 * This class is also the only place in the module where Customer's services are named. That is the
 * point of the CustomerLookup port (ADR-008): the dependency is visible in one file instead of
 * spread through the application layer.
 */
@Configuration
public class OrderConfiguration {

    @Bean
    OrderRepository orderRepository(SpringDataOrderRepository springDataOrderRepository) {
        return new JpaOrderRepository(springDataOrderRepository);
    }

    @Bean
    CustomerLookup customerLookup(GetCustomerService getCustomerService) {
        return new CustomerModuleLookup(getCustomerService);
    }

    /**
     * The OutboxWriter and the TransactionTemplate are both shared beans, and both are visible here
     * on purpose: this is where a reviewer can see that creating an order takes a transaction and
     * emits an event, without opening the service.
     */
    @Bean
    CreateOrderService createOrderService(
        OrderRepository orderRepository,
        CustomerLookup customerLookup,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CreateOrderService(
            orderRepository, customerLookup, outboxWriter, transactionTemplate, clock
        );
    }

    @Bean
    GetOrderService getOrderService(OrderRepository orderRepository) {
        return new GetOrderService(orderRepository);
    }

    @Bean
    ListOrdersService listOrdersService(OrderRepository orderRepository) {
        return new ListOrdersService(orderRepository);
    }

    @Bean
    CancelOrderService cancelOrderService(
        OrderRepository orderRepository,
        GetOrderService getOrderService,
        Clock clock
    ) {
        return new CancelOrderService(orderRepository, getOrderService, clock);
    }
}
