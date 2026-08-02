package com.paymesh.customer.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * SDD 10.3's PATCH. A null field means "leave it alone".
 * <p>
 * {@code merchantReference} is absent deliberately: it is the merchant's own key for this customer
 * and very likely the join key in their system, so changing it is creating a different customer.
 */
public record UpdateCustomerRequest(

    @Email(message = "Email must be a valid address")
    @Size(max = 320, message = "Email must be at most 320 characters")
    String email,

    @Size(max = 200, message = "Name must be at most 200 characters")
    String name,

    @Size(max = 32, message = "Phone must be at most 32 characters")
    String phone
) {
}
