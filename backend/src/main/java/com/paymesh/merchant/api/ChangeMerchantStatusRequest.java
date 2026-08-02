package com.paymesh.merchant.api;

import jakarta.validation.constraints.Size;

/**
 * @param reason required by the domain for SUSPENDED and CLOSED, optional for ACTIVE. Not enforced
 *     here with {@code @NotBlank} because the requirement depends on the target status, and a
 *     validation annotation cannot see the path. {@code MerchantStatusChange} refuses it, and so
 *     does {@code ck_merchant_status_history_reason}.
 */
public record ChangeMerchantStatusRequest(
    @Size(max = 200, message = "Reason must be at most 200 characters")
    String reason
) {
}
