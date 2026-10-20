package com.paymesh.customer.application;

/**
 * This customer already has this card on file.
 * <p>
 * Refused rather than deduplicated silently: two rows for one card is something the merchant then
 * has to reason about, and "the" saved card would depend on insertion order.
 */
public final class PaymentMethodAlreadyAttachedException extends RuntimeException {

    /** The same CARD, recognised by its fingerprint, is already on file and live. */
    public static PaymentMethodAlreadyAttachedException sameCard(String customerId) {
        return new PaymentMethodAlreadyAttachedException(
            "Customer " + customerId + " already has this card attached"
        );
    }

    /**
     * The same PROVIDER TOKEN has been used before, for this merchant and provider.
     * <p>
     * A distinct message, because it means something different and the fix is different: a provider
     * token is the provider's handle for one stored instrument, so re-attaching a card after
     * detaching it needs a NEW token from the provider rather than the old one. Reporting it as
     * "this card is already attached" would send an integrator looking at a card that is not there.
     */
    public static PaymentMethodAlreadyAttachedException sameProviderToken(String customerId) {
        return new PaymentMethodAlreadyAttachedException(
            "That provider token has already been used; re-attaching a card needs a fresh one"
        );
    }

    private PaymentMethodAlreadyAttachedException(String message) {
        super(message);
    }
}
