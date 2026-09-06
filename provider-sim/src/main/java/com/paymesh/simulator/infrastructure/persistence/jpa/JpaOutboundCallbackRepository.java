package com.paymesh.simulator.infrastructure.persistence.jpa;

import com.paymesh.simulator.application.OutboundCallbackRepository;
import com.paymesh.simulator.domain.OutboundCallback;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed implementation of the {@link OutboundCallbackRepository} port. */
public final class JpaOutboundCallbackRepository implements OutboundCallbackRepository {

    private final SpringDataOutboundCallbackRepository callbacks;

    public JpaOutboundCallbackRepository(SpringDataOutboundCallbackRepository callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public OutboundCallback save(OutboundCallback callback) {
        callbacks.save(SimulatorJpaMapper.toEntity(callback));
        return callback;
    }

    @Override
    public List<String> findDue(Instant now, int limit) {
        // RAW ids: mapping here would sit outside the dispatcher's per-item try/catch. See the port.
        return callbacks.findDue(now, PageRequest.of(0, limit));
    }

    @Override
    public Optional<OutboundCallback> findPendingForUpdate(String outboundCallbackId) {
        return callbacks.findPendingForUpdate(outboundCallbackId).map(SimulatorJpaMapper::toDomain);
    }

    @Override
    public List<OutboundCallback> findByCallbackReference(String callbackReference) {
        return callbacks.findByCallbackReferenceOrderByCreatedAtAsc(callbackReference).stream()
            .map(SimulatorJpaMapper::toDomain)
            .toList();
    }
}
