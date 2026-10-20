package com.paymesh.payment.application;

/**
 * One candidate for the abandoned-checkout sweep: the two identifiers needed to cancel it,
 * <b>unparsed</b>. The sibling of {@code ExpirableOrder}, for the same reason.
 *
 * <h2>WHY THE FIELDS ARE {@code String}</h2>
 *
 * {@code MerchantId.from} and {@code PaymentIntentId.from} <b>validate</b>: they throw on anything
 * that is not {@code prefix_uuid}, and neither {@code merchants.merchant_id} nor
 * {@code payment_intents.payment_intent_id} carries a format CHECK, so a malformed identifier is a
 * row PostgreSQL accepts today. Building the value objects in the repository would put that throw
 * outside {@code CancelAbandonedPaymentIntentsService}'s per-item try -- where one bad row takes
 * the whole sweep, permanently, because it has the oldest {@code updated_at} and therefore leads
 * every subsequent batch. Open item 2.
 * <p>
 * So these stay raw and the service parses them inside its {@code try}.
 */
public record AbandonedIntent(String merchantId, String paymentIntentId) {
}
