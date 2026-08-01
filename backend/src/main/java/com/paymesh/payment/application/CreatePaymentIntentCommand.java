package com.paymesh.payment.application;

import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Map;

/**
 * The input to "create a payment intent".
 * <p>
 * merchantId comes from the verified access token, never from the request body -- an API record
 * cannot carry it, so it cannot be spoofed. There is no status field either: creation always
 * produces REQUIRES_PAYMENT_METHOD and a caller never names a state.
 */
public record CreatePaymentIntentCommand(
    MerchantId merchantId,
    String orderId,
    String customerId,
    long amountMinor,
    String currency,
    CaptureMethod captureMethod,
    String description,
    Map<String, String> metadata
) {
}
