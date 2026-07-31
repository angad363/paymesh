package com.paymesh.order.application;

import com.paymesh.shared.tenant.MerchantId;

/**
 * The one question Order asks Customer: does this merchant have that buyer?
 * <p>
 * This is a one-implementation interface, which this project normally cuts. It stays because it is
 * the module boundary ADR-001 exists to protect: the CONSUMER owns the contract, so the whole of
 * {@code com.paymesh.order} depends on this three-line question rather than on Customer's services,
 * and extracting Customer into its own service later changes one adapter class instead of every
 * call site. See ADR-008.
 * <p>
 * The answer is advisory. A customer can be deleted between the check and the insert; the composite
 * foreign key on {@code (merchant_id, customer_id)} is what actually guarantees the link. This exists
 * to turn that constraint violation into a readable 422 instead of a 500.
 */
public interface CustomerLookup {

    boolean exists(MerchantId merchantId, String customerId);
}
