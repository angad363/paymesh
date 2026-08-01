package com.paymesh.payment.infrastructure.persistence.jpa;

import com.paymesh.payment.application.DuplicateProviderCallbackException;
import com.paymesh.payment.application.ProviderCallbackRepository;
import com.paymesh.payment.domain.ProviderCallback;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PostgreSQL-backed implementation of the ProviderCallbackRepository port.
 * <p>
 * There is no {@code @Transactional} here, and its absence is the design (ADR-012). The insert joins
 * whatever transaction the caller opened, so a duplicate takes the accompanying state change down
 * with it instead of leaving a row that swallows the provider's event forever.
 */
public final class JpaProviderCallbackRepository implements ProviderCallbackRepository {

    private static final String DEDUPLICATION_KEY = "pk_provider_callbacks";

    private final SpringDataProviderCallbackRepository callbacks;

    public JpaProviderCallbackRepository(SpringDataProviderCallbackRepository callbacks) {
        this.callbacks = callbacks;
    }

    /**
     * Flushed rather than left for the commit, and here that is not merely for a better stack trace.
     * <p>
     * <b>The flush is what makes the collision happen at this line, inside the transaction.</b> Left
     * to commit time the violation would surface after the transition had been decided and written,
     * and -- more importantly -- a CONCURRENT duplicate would not block where the design says it
     * blocks. Flushing here puts the second inserter on the index entry until the first transaction
     * resolves: if the first commits, this one gets its violation and no-ops; if the first ROLLS
     * BACK, this insert succeeds and the event is correctly applied. That last case is the one a
     * reviewer talks themselves out of, and it is why the insert is in the transaction at all.
     */
    @Override
    public void insert(ProviderCallback callback) {
        try {
            callbacks.saveAndFlush(new ProviderCallbackJpaEntity(
                callback.provider(),
                callback.externalEventId(),
                callback.merchantId().value(),
                callback.paymentIntentId().value(),
                callback.payloadHash(),
                callback.payload(),
                callback.outcome().name(),
                callback.occurredAt(),
                callback.receivedAt(),
                callback.processedAt()
            ));
        } catch (DataIntegrityViolationException exception) {
            // Narrowed BY CONSTRAINT NAME, like the other adapters here. The other integrity failure
            // reachable on this insert is fk_provider_callbacks_intent, and reporting a callback
            // pointed at a missing or another tenant's intent as "already delivered" would answer
            // 200 to an event that was never processed -- the one outcome this table exists to make
            // impossible.
            if (ConstraintViolations.violates(exception, DEDUPLICATION_KEY)) {
                throw new DuplicateProviderCallbackException(
                    callback.provider(), callback.externalEventId()
                );
            }

            throw exception;
        }
    }
}
