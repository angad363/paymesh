package com.paymesh.refund.application;

import com.paymesh.refund.domain.Refund;
import com.paymesh.refund.domain.RefundId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;
import java.util.Optional;

public interface RefundRepository {

    /**
     * @throws RefundAlreadyRequestedException when the merchant reference is taken. Detected by
     *     {@code uq_refunds_merchant_reference}, not by a pre-read -- two concurrent creates with
     *     one reference both find nothing, and the unique index picks the winner.
     * @throws RefundExceedsCapturedAmountException when the deferred over-refund trigger refuses
     *     the transaction at COMMIT
     */
    Refund save(Refund refund);

    Optional<Refund> findByRefundId(MerchantId merchantId, RefundId refundId);

    /** Row-locked, for a transition that must not race another. */
    Optional<Refund> findForUpdate(RefundId refundId);

    List<Refund> findPage(MerchantId merchantId, RefundCursor cursor, int limit);

    /** Every non-terminal-and-not-failed refund of one payment. Used to report the refundable head-room. */
    long activeTotalMinor(String paymentIntentId);

    /**
     * Refunds stuck in PROCESSING since before {@code threshold}, oldest first.
     * <p>
     * Oldest first so a backlog drains in the order it accumulated, and bounded so one pass cannot
     * take an unbounded lock footprint.
     */
    List<Refund> findProcessingOlderThan(java.time.Instant threshold, int limit);
}
