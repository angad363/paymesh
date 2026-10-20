package com.paymesh.customer.application;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;

import java.time.Clock;
import java.time.Instant;

public final class CreateCustomerService {

    private final CustomerRepository customerRepository;
    private final Clock clock;

    public CreateCustomerService(CustomerRepository customerRepository, Clock clock) {
        this.customerRepository = customerRepository;
        this.clock = clock;
    }

    public Customer create(CreateCustomerCommand command) {
        if(command == null) {
            throw new IllegalArgumentException("Create Customer Command cannot be null");
        }

        Customer customer = Customer.create(
            CustomerId.generate(),
            command.merchantId(),
            command.merchantReference(),
            command.email(),
            command.name(),
            command.phone(),
            Instant.now(clock)
        );

        // Read the reference back off the aggregate, not the command: the domain trimmed it and
        // turned blank into null, so this checks the value that will actually hit the constraint.
        String merchantReference = customer.merchantReference();

        if(merchantReference != null
            && customerRepository.existsByMerchantReference(customer.merchantId(), merchantReference)) {
            throw new CustomerReferenceAlreadyExistsException(merchantReference);
        }

        return customerRepository.save(customer);
    }
}
