package com.paymesh.customer.infrastructure.config;

import com.paymesh.customer.application.AttachPaymentMethodTokenService;
import com.paymesh.customer.application.ChangeCustomerStatusService;
import com.paymesh.customer.application.CreateCustomerService;
import com.paymesh.customer.application.CustomerStatusHistoryRepository;
import com.paymesh.customer.application.PaymentMethodTokenRepository;
import com.paymesh.customer.application.UpdateCustomerService;
import com.paymesh.customer.infrastructure.persistence.jpa.JpaCustomerStatusHistoryRepository;
import com.paymesh.customer.infrastructure.persistence.jpa.JpaPaymentMethodTokenRepository;
import com.paymesh.customer.infrastructure.persistence.jpa.SpringDataCustomerStatusHistoryRepository;
import com.paymesh.customer.infrastructure.persistence.jpa.SpringDataPaymentMethodTokenRepository;
import com.paymesh.shared.outbox.application.OutboxWriter;
import org.springframework.transaction.support.TransactionTemplate;
import com.paymesh.customer.application.CustomerRepository;
import com.paymesh.customer.application.GetCustomerService;
import com.paymesh.customer.infrastructure.persistence.jpa.JpaCustomerRepository;
import com.paymesh.customer.infrastructure.persistence.jpa.SpringDataCustomerRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Explicit wiring for the customer capability (no component scanning of application/domain classes).
 * The Clock is injected, not declared: SharedConfiguration owns it, because time is needed by every
 * capability and belongs to none of them.
 */
@Configuration
public class CustomerConfiguration {

    @Bean
    CustomerRepository customerRepository(SpringDataCustomerRepository springDataCustomerRepository) {
        return new JpaCustomerRepository(springDataCustomerRepository);
    }

    @Bean
    CreateCustomerService createCustomerService(CustomerRepository customerRepository, Clock clock) {
        return new CreateCustomerService(customerRepository, clock);
    }

    @Bean
    GetCustomerService getCustomerService(CustomerRepository customerRepository) {
        return new GetCustomerService(customerRepository);
    }

    @Bean
    CustomerStatusHistoryRepository customerStatusHistoryRepository(
        SpringDataCustomerStatusHistoryRepository history
    ) {
        return new JpaCustomerStatusHistoryRepository(history);
    }

    @Bean
    PaymentMethodTokenRepository paymentMethodTokenRepository(
        SpringDataPaymentMethodTokenRepository tokens
    ) {
        return new JpaPaymentMethodTokenRepository(tokens);
    }

    @Bean
    UpdateCustomerService updateCustomerService(
        CustomerRepository customerRepository,
        GetCustomerService getCustomerService,
        Clock clock
    ) {
        return new UpdateCustomerService(customerRepository, getCustomerService, clock);
    }

    @Bean
    ChangeCustomerStatusService changeCustomerStatusService(
        CustomerRepository customerRepository,
        CustomerStatusHistoryRepository customerStatusHistoryRepository,
        GetCustomerService getCustomerService,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new ChangeCustomerStatusService(
            customerRepository, customerStatusHistoryRepository, getCustomerService,
            transactionTemplate, clock
        );
    }

    @Bean
    AttachPaymentMethodTokenService attachPaymentMethodTokenService(
        PaymentMethodTokenRepository paymentMethodTokenRepository,
        GetCustomerService getCustomerService,
        OutboxWriter outboxWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new AttachPaymentMethodTokenService(
            paymentMethodTokenRepository, getCustomerService, outboxWriter, transactionTemplate,
            clock
        );
    }
}
