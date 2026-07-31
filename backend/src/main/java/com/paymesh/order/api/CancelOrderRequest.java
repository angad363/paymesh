package com.paymesh.order.api;

import jakarta.validation.constraints.Size;

/**
 * The body of a cancel request. Every field is optional, and so is the body itself -- cancelling
 * without saying why is a legitimate thing for a merchant to do.
 */
public record CancelOrderRequest(

    @Size(max = 200, message = "Reason must not exceed 200 characters")
    String reason
) {
}
