package com.paymesh.customer.api;

import com.paymesh.customer.domain.Customer;

import java.time.Instant;

/**
 * The lookup hashes are deliberately not exposed. They are an internal index key, and publishing
 * them would let a holder of this response confirm a guessed email or phone number offline.
 */
public record CustomerResponse(
    String id,
    String merchantId,
    String merchantReference,
    String email,
    String name,
    String phone,
    String status,
    Instant createdAt,
    Instant updatedAt
) {
    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
            customer.customerId().value(),
            customer.merchantId().value(),
            customer.merchantReference(),
            customer.email(),
            customer.name(),
            customer.phone(),
            customer.status().name(),
            customer.createdAt(),
            customer.updatedAt()
        );
    }
}
