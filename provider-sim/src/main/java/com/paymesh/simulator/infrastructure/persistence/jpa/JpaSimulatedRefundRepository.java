package com.paymesh.simulator.infrastructure.persistence.jpa;

import com.paymesh.simulator.application.IdempotencyKeyRaceLostException;
import com.paymesh.simulator.application.SimulatedRefundRepository;
import com.paymesh.simulator.domain.SimulatedRefund;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed implementation of the {@link SimulatedRefundRepository} port. */
public final class JpaSimulatedRefundRepository implements SimulatedRefundRepository {

    private static final String IDEMPOTENCY_KEY = "uq_provider_refunds_idempotency_key";

    private final SpringDataSimulatedRefundRepository refunds;

    public JpaSimulatedRefundRepository(SpringDataSimulatedRefundRepository refunds) {
        this.refunds = refunds;
    }

    @Override
    public SimulatedRefund save(SimulatedRefund refund) {
        try {
            refunds.saveAndFlush(SimulatorJpaMapper.toEntity(refund));
        } catch (DataIntegrityViolationException exception) {
            if (SimulatorConstraintViolations.violates(exception, IDEMPOTENCY_KEY)) {
                throw new IdempotencyKeyRaceLostException(refund.idempotencyKey());
            }

            throw exception;
        }

        return refund;
    }

    @Override
    public Optional<SimulatedRefund> findByIdempotencyKey(String idempotencyKey) {
        return refunds.findByIdempotencyKey(idempotencyKey).map(SimulatorJpaMapper::toDomain);
    }

    @Override
    public List<SimulatedRefund> findCreatedBetween(Instant fromInclusive, Instant toExclusive) {
        return refunds.findCreatedBetween(fromInclusive, toExclusive).stream()
            .map(SimulatorJpaMapper::toDomain)
            .toList();
    }
}
