package com.paymesh.customer.application;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Optional;

/**
 * The customer capability's persistence port.
 * <p>
 * Every read takes a MerchantId. That is the tenant boundary expressed as a method signature: there
 * is deliberately no findByCustomerId(CustomerId), because a caller holding only an id -- guessed,
 * leaked, or copied from another merchant's response -- must not be able to reach a row. Adding such
 * an overload would silently remove tenant isolation from every caller at once.
 */
public interface CustomerRepository {

    boolean existsByMerchantReference(MerchantId merchantId, String merchantReference);

    Customer save(Customer customer);

    Optional<Customer> findByCustomerId(MerchantId merchantId, CustomerId customerId);
}
