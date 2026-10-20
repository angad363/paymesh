package com.paymesh.customer.application;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Test double for the persistence port. Enforces the same tenant scoping the real adapter gets from
 * the "where merchant_id = ?" predicate, so a service test that leaks across tenants fails here too
 * rather than only in the container-backed test.
 */
final class InMemoryCustomerRepository implements CustomerRepository {

    private final List<Customer> customers = new ArrayList<>();

    @Override
    public boolean existsByMerchantReference(MerchantId merchantId, String merchantReference) {
        return customers.stream()
            .anyMatch(customer -> customer.merchantId().equals(merchantId)
                && merchantReference.equals(customer.merchantReference()));
    }

    @Override
    public Customer save(Customer customer) {
        customers.add(customer);
        return customer;
    }

    @Override
    public Optional<Customer> findByCustomerId(MerchantId merchantId, CustomerId customerId) {
        return customers.stream()
            .filter(customer -> customer.merchantId().equals(merchantId)
                && customer.customerId().equals(customerId))
            .findFirst();
    }
}
