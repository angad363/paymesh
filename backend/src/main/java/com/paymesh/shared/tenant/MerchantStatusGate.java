package com.paymesh.shared.tenant;

/**
 * May this merchant perform authenticated writes?
 *
 * <h2>A PORT DECLARED IN {@code shared}, IMPLEMENTED BY {@code merchant}</h2>
 *
 * Every authenticated write in the platform needs this one boolean, so asking each capability to
 * declare its own {@code MerchantLookup} would mean four identical ports for one question and four
 * places to forget it. Declaring it here and letting the Merchant module implement it keeps the
 * arrow pointing the way it already points -- {@code merchant} may see {@code shared}, and
 * {@code shared} still names no capability.
 * <p>
 * It is the same shape as Order's {@code PaymentActivityLookup}, which Order declares and Payment
 * implements (ADR-008). The only difference is that the consumer is the platform rather than a
 * capability.
 *
 * <h2>Why it is a boolean rather than a status</h2>
 *
 * Returning {@code MerchantStatus} would put the merchant enum in {@code shared} and therefore in
 * every module's reach, and every caller would then have to know which values permit trading. One
 * module owns that rule; everyone else asks the question.
 */
public interface MerchantStatusGate {

    /**
     * @return false when the merchant is suspended, closed, still unverified, or does not exist.
     *     A caller cannot distinguish those, deliberately: the answer to all four is the same
     *     refusal, and telling a caller which would leak the state of a tenant they may not be able
     *     to see.
     */
    boolean canTransact(MerchantId merchantId);
}
