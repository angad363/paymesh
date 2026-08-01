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
     * The same answer, but the order row is held still until the caller's transaction ends.
     * <p>
     * A PLAIN READ IS A CHECK, NOT A LOCK, and payability is the one fact Payment reads that another
     * module can change underneath it. An order cancelled between the lookup and the insert leaves a
     * live intent collecting against a cancelled order, and Order cannot prevent that from its side
     * because it must not know Payment exists (design section 0.5). Locking the row is what makes
     * the create path's read and write one decision instead of two.
     * <p>
     * It does not, and cannot, stop an order being cancelled AFTER the intent is committed -- see
     * ADR-013. Confirm's re-read is the guard for that, and neither replaces the other. Confirm
     * deliberately uses {@link #find} rather than this: ADR-013 section 2 explains what the lock
     * would cost there.
     * <p>
     * MUST be called inside a transaction.
     */
    Optional<PayableOrder> findForUpdate(MerchantId merchantId, String orderId);

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
