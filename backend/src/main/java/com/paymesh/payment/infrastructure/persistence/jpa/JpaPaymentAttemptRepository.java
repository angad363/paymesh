package com.paymesh.payment.infrastructure.persistence.jpa;

import com.paymesh.payment.application.PaymentAttemptAlreadyStartedException;
import com.paymesh.payment.application.PaymentAttemptRepository;
import com.paymesh.payment.domain.PaymentAttempt;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PostgreSQL-backed implementation of the application's PaymentAttemptRepository port.
 * Everything JPA stays on this side of the interface; the services see only domain types.
 */
public final class JpaPaymentAttemptRepository implements PaymentAttemptRepository {

    private static final String ATTEMPT_NUMBER_CONSTRAINT = "uq_payment_attempts_intent_number";

    private final SpringDataPaymentAttemptRepository attempts;

    public JpaPaymentAttemptRepository(SpringDataPaymentAttemptRepository attempts) {
        this.attempts = attempts;
    }

    /**
     * Counted rather than read off a MAX, because attempt numbers are dense: they start at 1 and
     * the unique constraint means none can be skipped, so the count IS the highest number. A MAX
     * would say the same thing and would need a null case for the first attempt.
     */
    @Override
    public int nextAttemptNumber(MerchantId merchantId, PaymentIntentId paymentIntentId) {
        return (int) attempts.countByMerchantIdAndPaymentIntentId(
            merchantId.value(), paymentIntentId.value()
        ) + 1;
    }

    /**
     * Flushed rather than left for the commit so a rejected row fails at the line that wrote it,
     * with a stack trace naming the confirmation, instead of surfacing as an opaque commit-time
     * failure. It is also what makes the losing side of a concurrent confirm block on the index
     * here, inside the transaction, rather than at some later point in it.
     */
    @Override
    public void append(PaymentAttempt attempt) {
        try {
            attempts.saveAndFlush(new PaymentAttemptJpaEntity(
                attempt.paymentAttemptId().value(),
                attempt.merchantId().value(),
                attempt.paymentIntentId().value(),
                attempt.attemptNumber(),
                attempt.provider(),
                attempt.status().name(),
                attempt.amountMinor(),
                attempt.currency(),
                // Absent request details are stored as SQL NULL rather than an empty JSON object,
                // so "the merchant sent none" and "the merchant sent {}" do not become two
                // spellings of the same thing in the column.
                attempt.requestPayload().isEmpty() ? null : attempt.requestPayload(),
                attempt.version(),
                attempt.createdAt(),
                attempt.updatedAt()
            ));
        } catch (DataIntegrityViolationException exception) {
            // Narrowed BY CONSTRAINT NAME, like JpaPaymentIntentRepository. The other integrity
            // failure reachable on this insert is fk_payment_attempts_intent, and reporting an
            // attempt hung off a missing or another tenant's intent as "already in flight" would
            // be both wrong and a hint about data the caller cannot see.
            if (ConstraintViolations.violates(exception, ATTEMPT_NUMBER_CONSTRAINT)) {
                throw new PaymentAttemptAlreadyStartedException(attempt.paymentIntentId());
            }

            throw exception;
        }
    }
}
