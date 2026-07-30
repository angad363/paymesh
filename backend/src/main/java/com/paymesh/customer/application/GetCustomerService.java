package com.paymesh.customer.application;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.shared.tenant.MerchantId;

public final class GetCustomerService {

    private final CustomerRepository customerRepository;

    public GetCustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /**
     * The merchantId argument is the authorization, not a filter: a customer belonging to another
     * merchant is reported as not found, so an id alone never proves the caller may read the row.
     */
    public Customer getById(MerchantId merchantId, CustomerId customerId) {
        if(merchantId == null) {
            throw new IllegalArgumentException("Merchant ID cannot be null");
        }

        if(customerId == null) {
            throw new IllegalArgumentException("Customer ID cannot be null");
        }

        return customerRepository
            .findByCustomerId(merchantId, customerId)
            .orElseThrow(
                () -> new CustomerNotFoundException(customerId)
            );
    }
}
