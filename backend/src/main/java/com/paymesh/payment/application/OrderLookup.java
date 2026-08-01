package com.paymesh.payment.application;

import com.paymesh.shared.tenant.MerchantId;

import java.util.Optional;

/**
 * What Payment needs to know about an order, defined by Payment (ADR-008).
 * <p>
 * Deliberately not the {@code boolean exists} shape {@code CustomerLookup} uses. Payment does not
 * merely need the order to be there: it has to compare the requested amount and currency against
 * the obligation, and it has to know whether the order is in a state that can be paid. The rule is
 * "the consumer states what it needs", not "copy the previous port's signature".
 * <p>
 * The adapter is tenant-scoped, so an order belonging to another merchant is empty here -- the same
 * answer as an order that does not exist. Callers must keep those two indistinguishable.
 */
public interface OrderLookup {

    Optional<PayableOrder> find(MerchantId merchantId, String orderId);

    /**
     * @param payable whether the order is in a state that can be collected against. Order's status
     *                enum deliberately does not cross the module boundary -- Payment has no reason
     *                to know its vocabulary, only the answer.
     */
    record PayableOrder(
        String orderId,
        String customerId,
        long amountMinor,
        String currency,
        boolean payable
    ) {
    }
}
