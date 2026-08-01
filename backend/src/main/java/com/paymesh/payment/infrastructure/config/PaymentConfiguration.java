package com.paymesh.payment.infrastructure.config;

import com.paymesh.order.application.GetOrderService;
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
import com.paymesh.payment.infrastructure.order.OrderModuleLookup;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentAttemptRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentIntentRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.JpaPaymentStateHistoryRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.SpringDataPaymentAttemptRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.SpringDataPaymentIntentRepository;
import com.paymesh.payment.infrastructure.persistence.jpa.SpringDataPaymentStateHistoryRepository;
import com.paymesh.shared.outbox.application.OutboxWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

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
