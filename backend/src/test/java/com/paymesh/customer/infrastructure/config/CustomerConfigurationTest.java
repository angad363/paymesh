package com.paymesh.customer.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.customer.application.CreateCustomerService;
import com.paymesh.customer.application.CustomerRepository;
import com.paymesh.customer.application.GetCustomerService;
import com.paymesh.customer.infrastructure.persistence.jpa.JpaCustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CustomerConfigurationTest {

    private final CustomerRepository customerRepository;
    private final CreateCustomerService createCustomerService;
    private final GetCustomerService getCustomerService;

    @Autowired
    CustomerConfigurationTest(
        CustomerRepository customerRepository,
        CreateCustomerService createCustomerService,
        GetCustomerService getCustomerService
    ) {
        this.customerRepository = customerRepository;
        this.createCustomerService = createCustomerService;
        this.getCustomerService = getCustomerService;
    }

    /**
     * Booting at all is half the assertion: ddl-auto=validate compares CustomerJpaEntity against the
     * Flyway-migrated schema, so a column that drifted from V3 fails here before any test body runs.
     */
    @Test
    void providesCustomerApplicationBeans() {
        assertNotNull(createCustomerService);
        assertNotNull(getCustomerService);

        assertInstanceOf(JpaCustomerRepository.class, customerRepository);
    }
}
