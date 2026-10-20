package com.paymesh.refund.infrastructure.persistence.jpa;

import com.paymesh.refund.application.RefundAlreadyRequestedException;
import com.paymesh.refund.application.RefundCursor;
import com.paymesh.refund.application.RefundExceedsCapturedAmountException;
import com.paymesh.refund.application.RefundRepository;
import com.paymesh.refund.domain.Refund;
import com.paymesh.refund.domain.RefundId;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class JpaRefundRepository implements RefundRepository {

    private final SpringDataRefundRepository refunds;

    public JpaRefundRepository(SpringDataRefundRepository refunds) {
        this.refunds = refunds;
    }

    /**
     * {@code saveAndFlush}, so a violation surfaces here rather than at an arbitrary later flush.
     *
     * <h2>THE OVER-REFUND TRIGGER IS DEFERRED AND WILL NOT FIRE AT THIS FLUSH</h2>
     *
     * It runs at COMMIT, after this method and after the service's transaction template has
     * returned. So the translation below catches the trigger only on the paths where something else
     * forces an earlier flush; the ordinary concurrent over-refund surfaces as a commit-time
     * exception out of the transaction boundary. Both end up as
     * {@link RefundExceedsCapturedAmountException} to the caller -- the service's pre-check is what
     * produces the readable answer in every non-racing case.
     */
    @Override
    public Refund save(Refund refund) {
        try {
            return RefundJpaMapper.toDomain(
                refunds.saveAndFlush(RefundJpaMapper.toEntity(refund))
            );
        } catch (DataIntegrityViolationException exception) {
            if (RefundConstraintViolations.violates(exception, "uq_refunds_merchant_reference")) {
                throw new RefundAlreadyRequestedException(refund.merchantReference());
            }

            if (RefundConstraintViolations.isOverRefund(exception)) {
                throw new RefundExceedsCapturedAmountException(refund.paymentIntentId());
            }

            throw exception;
        }
    }

    @Override
    public Optional<Refund> findByRefundId(MerchantId merchantId, RefundId refundId) {
        return refunds.findByMerchantIdAndRefundId(merchantId.value(), refundId.value())
            .map(RefundJpaMapper::toDomain);
    }

    @Override
    public Optional<Refund> findForUpdate(RefundId refundId) {
        return refunds.findForUpdate(refundId.value()).map(RefundJpaMapper::toDomain);
    }

    @Override
    public List<Refund> findPage(MerchantId merchantId, RefundCursor cursor, int limit) {
        return refunds.findPage(
                merchantId.value(), cursor.createdAt(), cursor.refundId(), Limit.of(limit)
            )
            .stream()
            .map(RefundJpaMapper::toDomain)
            .toList();
    }

    @Override
    public List<String> findProcessingOlderThan(Instant threshold, int limit) {
        // RAW, unparsed: RefundId.from validates and throws, and the sweep's per-item try/catch is
        // the only place that may happen. Open item 2.
        return refunds.findProcessingOlderThan(threshold, Limit.of(limit));
    }

    @Override
    public long activeTotalMinor(String paymentIntentId) {
        return refunds.activeTotalMinor(paymentIntentId);
    }
}
