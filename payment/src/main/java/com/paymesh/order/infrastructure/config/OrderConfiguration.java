package com.paymesh.order.infrastructure.config;

import com.paymesh.customer.application.GetCustomerService;
import com.paymesh.order.application.ApplyPaymentSucceededService;
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
import com.paymesh.order.infrastructure.events.PaymentSucceededHandler;
import com.paymesh.order.infrastructure.persistence.jpa.JpaOrderRepository;
import com.paymesh.order.infrastructure.persistence.jpa.JpaOrderStateHistoryRepository;
import com.paymesh.order.infrastructure.persistence.jpa.SpringDataOrderRepository;
import com.paymesh.order.infrastructure.persistence.jpa.SpringDataOrderStateHistoryRepository;
import com.paymesh.order.infrastructure.schedule.OrderExpirySweeper;
import com.paymesh.shared.outbox.application.OutboxWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
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
 * <p>
 * NOTE WHAT IS NOT NAMED HERE: the implementation of {@code PaymentActivityLookup}. Order declares
 * that port and injects it by its own type; the implementation is registered by
 * {@code PaymentConfiguration}, on the side of the boundary already allowed to import the other. So
 * Order still contains no reference to {@code com.paymesh.payment} anywhere -- including this file
 * -- and {@code ModuleBoundaryTest.orderNeverImportsPayment} keeps its empty allowlist (ADR-014).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OrderExpiryProperties.class)
public class OrderConfiguration {

    @Bean
    OrderRepository orderRepository(SpringDataOrderRepository springDataOrderRepository) {
        return new JpaOrderRepository(springDataOrderRepository);
    }

    @Bean
    OrderStateHistoryRepository orderStateHistoryRepository(
        SpringDataOrderStateHistoryRepository springDataOrderStateHistoryRepository
    ) {
        return new JpaOrderStateHistoryRepository(springDataOrderStateHistoryRepository);
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
        OrderStateHistoryRepository orderStateHistoryRepository,
        CustomerLookup customerLookup,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CreateOrderService(
            orderRepository,
            orderStateHistoryRepository,
            customerLookup,
            outboxWriter,
            transactionTemplate,
            clock
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
        OrderStateHistoryRepository orderStateHistoryRepository,
        GetOrderService getOrderService,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CancelOrderService(
            orderRepository,
            orderStateHistoryRepository,
            getOrderService,
            outboxWriter,
            transactionTemplate,
            clock
        );
    }

    /**
     * The sweep logic. Declared unconditionally, even when the timer below is switched off: it is an
     * ordinary object, it starts nothing on its own, and a test or an operator running a single
     * sweep by hand should not have to enable a scheduler to do it.
     */
    @Bean
    ExpireOrdersService expireOrdersService(
        OrderRepository orderRepository,
        OrderStateHistoryRepository orderStateHistoryRepository,
        GetOrderService getOrderService,
        PaymentActivityLookup paymentActivityLookup,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock,
        OrderExpiryProperties properties
    ) {
        return new ExpireOrdersService(
            orderRepository,
            orderStateHistoryRepository,
            getOrderService,
            paymentActivityLookup,
            outboxWriter,
            transactionTemplate,
            clock,
            properties.batchSize()
        );
    }

    /**
     * The timer, and the only conditional bean in this file.
     * <p>
     * With the bean absent there is no {@code @Scheduled} method to register at all, so switching
     * {@code paymesh.orders.expiry-sweep.enabled} off genuinely stops the job rather than running a
     * no-op on every tick. It defaults ON, because a sweeper that is off by default is a sweeper
     * nobody remembers to turn on and the open item it closes is real.
     * <p>
     * <b>The dev profile turns it off</b>, because that is the profile the test suite runs under and
     * a timer mutating rows underneath a test is a flake generator. See {@code application-dev.yaml}.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "paymesh.orders.expiry-sweep", name = "enabled", matchIfMissing = true
    )
    OrderExpirySweeper orderExpirySweeper(ExpireOrdersService expireOrdersService) {
        return new OrderExpirySweeper(expireOrdersService);
    }

    /**
     * The rules a successful payment applies to an order (ADR-016).
     * <p>
     * NOTE THE MISSING ARGUMENT: there is no {@code TransactionTemplate}, and every other write
     * service above takes one. This one runs inside the transaction {@code EventDispatcher} opened
     * around the {@code processed_events} claim, and opening a second would let the state change
     * commit independently of the row saying the event was consumed. Its javadoc says so; this is
     * where a reviewer sees it without opening the file.
     */
    @Bean
    ApplyPaymentSucceededService applyPaymentSucceededService(
        OrderRepository orderRepository,
        OrderStateHistoryRepository orderStateHistoryRepository,
        GetOrderService getOrderService,
        OutboxWriter outboxWriter
    ) {
        return new ApplyPaymentSucceededService(
            orderRepository, orderStateHistoryRepository, getOrderService, outboxWriter
        );
    }

    /**
     * ORDER'S SUBSCRIPTION TO {@code payment.succeeded}, DECLARED ON ORDER'S SIDE.
     * <p>
     * {@code EventDispatcher} takes {@code List<EventHandler>} and Spring fills it from beans like
     * this one, so the platform's wiring never names a capability and a capability's subscriptions
     * are visible in its own configuration. Deleting this bean unsubscribes Order, and nothing in
     * {@code shared} needs to know.
     * <p>
     * <b>This does not put Payment in Order's import graph.</b> The bean's type is Order's own class
     * and the event type is a string; {@code ModuleBoundaryTest.orderNeverImportsPayment} keeps its
     * empty allowlist, which is the constraint the whole consumer design had to satisfy.
     */
    @Bean
    PaymentSucceededHandler paymentSucceededHandler(
        ApplyPaymentSucceededService applyPaymentSucceededService
    ) {
        return new PaymentSucceededHandler(applyPaymentSucceededService);
    }
}
