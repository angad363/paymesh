package com.paymesh.refund.application;

import com.paymesh.refund.domain.Refund;
import com.paymesh.refund.domain.RefundId;
import com.paymesh.refund.domain.RefundStateChange;
import com.paymesh.refund.domain.RefundStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Withdraws a refund before the provider has acted on it. SDD 16.3's cancel endpoint.
 *
 * <h2>THE WINDOW IS NARROW AND REAL</h2>
 *
 * {@code CreateRefundService} writes PENDING and submits in one transaction, so a refund is
 * PROCESSING by the time the caller sees it and this will answer 409 almost every time. That is
 * the honest behaviour rather than a defect: PROCESSING means the provider may already have moved
 * the money, and reporting CANCELLED then would be PayMesh's opinion contradicting the customer's
 * bank statement.
 * <p>
 * The endpoint exists because the state it guards is real -- a refund that failed to submit stays
 * PENDING, and without this there would be no way to clear it and free the amount it holds against
 * the captured total.
 */
public final class CancelRefundService {

    private final RefundRepository refunds;
    private final RefundStateHistoryRepository history;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CancelRefundService(
        RefundRepository refunds,
        RefundStateHistoryRepository history,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.refunds = refunds;
        this.history = history;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Refund cancel(MerchantId merchantId, RefundId refundId, String actorId) {
        Instant now = Instant.now(clock);

        return transactions.execute(status -> {
            // Scoped read first, so another merchant's refund is a 404 rather than a lock taken on
            // a row the caller may not see. Then locked, so a callback landing at the same moment
            // serializes behind this rather than racing the state check.
            refunds.findByRefundId(merchantId, refundId)
                .orElseThrow(() -> new RefundNotFoundException(refundId.value()));

            Refund refund = refunds.findForUpdate(refundId)
                .orElseThrow(() -> new RefundNotFoundException(refundId.value()));

            RefundStatus from = refund.status();
            Refund cancelled = refunds.save(refund.cancel(now));

            history.append(new RefundStateChange(
                cancelled.merchantId(), cancelled.refundId(), from, RefundStatus.CANCELLED,
                RefundStateChange.ActorType.MERCHANT, actorId, "Cancelled by the merchant", now
            ));

            return cancelled;
        });
    }
}
