package com.paymesh.ledger.application;

/**
 * One payment's net contribution to a merchant's available balance, in one currency.
 *
 * @param amountMinor signed. Positive is money released and not yet refunded; negative is a payment
 *     refunded past its own release, which Settlement carries into the batch as an adjustment
 *     rather than dropping -- dropping it would pay out more than the merchant has
 */
public record AvailableContribution(String currency, String paymentIntentId, long amountMinor) {
}
