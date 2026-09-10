package com.paymesh.audit.infrastructure.persistence.jpa;

import com.paymesh.audit.application.AuditEventQuery;
import com.paymesh.audit.application.AuditEventRepository;
import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.audit.domain.AuditEventId;
import com.paymesh.audit.domain.AuditWindow;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed {@link AuditEventRepository}. */
public final class JpaAuditEventRepository implements AuditEventRepository {

    // The bounds a windowless search spans. Wide enough to hold every audit row ever written and
    // within PostgreSQL's timestamptz range, so the search query can apply an unconditional time
    // bound rather than a null-guard PostgreSQL cannot type. See the query's javadoc.
    private static final Instant FLOOR = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant CEILING = Instant.parse("9999-12-31T23:59:59Z");

    private final SpringDataAuditEventRepository events;

    public JpaAuditEventRepository(SpringDataAuditEventRepository events) {
        this.events = events;
    }

    @Override
    public AuditEvent append(AuditEvent event) {
        return AuditJpaMapper.toDomain(
            events.saveAndFlush(AuditJpaMapper.toEntity(event))
        );
    }

    @Override
    public Optional<AuditEvent> findById(AuditEventId id) {
        return events.findById(id.value()).map(AuditJpaMapper::toDomain);
    }

    @Override
    public List<AuditEvent> search(AuditEventQuery query) {
        AuditWindow window = query.window();

        return events.search(
            query.merchantId() == null ? null : query.merchantId().value(),
            query.action(),
            query.actorId(),
            window == null ? FLOOR : window.from(),
            window == null ? CEILING : window.to(),
            PageRequest.ofSize(query.limit())
        ).stream().map(AuditJpaMapper::toDomain).toList();
    }

    @Override
    public List<AuditEvent> findInWindow(AuditWindow window, MerchantId merchantFilter, int limit) {
        Instant from = window.from();
        Instant to = window.to();

        return events.findInWindow(
            from,
            to,
            merchantFilter == null ? null : merchantFilter.value(),
            PageRequest.ofSize(limit)
        ).stream().map(AuditJpaMapper::toDomain).toList();
    }
}
