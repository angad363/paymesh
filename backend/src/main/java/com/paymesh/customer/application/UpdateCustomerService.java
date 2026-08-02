package com.paymesh.customer.application;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Clock;
import java.time.Instant;

/**
 * SDD 10.3's {@code PATCH /v1/customers/{id}}. Contact details change; a customer record that
 * cannot be corrected forces a merchant to create a duplicate.
 * <p>
 * A null field means "leave it alone", which is what makes this a PATCH rather than a PUT that
 * silently blanks whatever the caller omitted.
 */
public final class UpdateCustomerService {

    private final CustomerRepository customers;
    private final GetCustomerService getCustomerService;
    private final Clock clock;

    public UpdateCustomerService(
        CustomerRepository customers,
        GetCustomerService getCustomerService,
        Clock clock
    ) {
        this.customers = customers;
        this.getCustomerService = getCustomerService;
        this.clock = clock;
    }

    public Customer update(
        MerchantId merchantId,
        CustomerId customerId,
        String email,
        String name,
        String phone
    ) {
        Customer customer = getCustomerService.getById(merchantId, customerId);

        return customers.save(customer.updateContact(email, name, phone, Instant.now(clock)));
    }
}
