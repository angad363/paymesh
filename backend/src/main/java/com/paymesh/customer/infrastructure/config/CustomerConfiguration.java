package com.paymesh.customer.infrastructure.config;

import com.paymesh.customer.application.CreateCustomerService;
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
}
