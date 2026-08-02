package com.paymesh.simulator.infrastructure.persistence.jpa;

import com.paymesh.simulator.application.IdempotencyKeyRaceLostException;
import com.paymesh.simulator.application.SimulatedPaymentRepository;
import com.paymesh.simulator.domain.SimulatedPayment;
import com.paymesh.simulator.domain.SimulatedPaymentId;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed implementation of the {@link SimulatedPaymentRepository} port. */
public final class JpaSimulatedPaymentRepository implements SimulatedPaymentRepository {

    private static final String IDEMPOTENCY_KEY = "uq_provider_payments_idempotency_key";

    private final SpringDataSimulatedPaymentRepository payments;

    public JpaSimulatedPaymentRepository(SpringDataSimulatedPaymentRepository payments) {
        this.payments = payments;
    }

    /**
     * Flushed rather than left for the commit, so the unique violation surfaces HERE.
     * <p>
     * At commit time the exception would escape the {@code TransactionTemplate} as an opaque
     * integrity failure with no idempotency key in it, and the service could not tell a lost race
     * from a genuine bug. Flushing turns it into a named, catchable answer at the line that caused
     * it.
     */
    @Override
    public SimulatedPayment save(SimulatedPayment payment) {
        try {
            payments.saveAndFlush(SimulatorJpaMapper.toEntity(payment));
        } catch (DataIntegrityViolationException exception) {
            // NARROWED BY CONSTRAINT NAME. The other integrity failures reachable on this write are
            // the two amount CHECKs, and reporting "you captured more than you authorized" as a lost
            // idempotency race would send the service off to re-read a row that does not exist.
            if (SimulatorConstraintViolations.violates(exception, IDEMPOTENCY_KEY)) {
                throw new IdempotencyKeyRaceLostException(payment.idempotencyKey());
            }

            throw exception;
        }

        return payment;
    }

    @Override
    public Optional<SimulatedPayment> findById(SimulatedPaymentId providerPaymentId) {
        return payments.findById(providerPaymentId.value()).map(SimulatorJpaMapper::toDomain);
    }

    @Override
    public Optional<SimulatedPayment> findByIdempotencyKey(String idempotencyKey) {
        return payments.findByIdempotencyKey(idempotencyKey).map(SimulatorJpaMapper::toDomain);
    }

    @Override
    public Optional<SimulatedPayment> findByIdForUpdate(SimulatedPaymentId providerPaymentId) {
        return payments.findByIdForUpdate(providerPaymentId.value()).map(SimulatorJpaMapper::toDomain);
    }

    @Override
    public List<SimulatedPayment> findCreatedBetween(Instant fromInclusive, Instant toExclusive) {
        return payments.findCreatedBetween(fromInclusive, toExclusive).stream()
            .map(SimulatorJpaMapper::toDomain)
            .toList();
    }
}
