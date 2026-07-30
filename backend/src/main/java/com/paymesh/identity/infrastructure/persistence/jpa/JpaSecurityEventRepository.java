package com.paymesh.identity.infrastructure.persistence.jpa;

import com.paymesh.identity.application.SecurityEventRepository;
import com.paymesh.identity.domain.SecurityEvent;

/**
 * PostgreSQL-backed implementation of the application's SecurityEventRepository
 * port.
 *
 * <p>No separate mapper class here, unlike User and RefreshToken: the audit trail
 * is write-only and never read back into a domain type, so there is only one
 * direction to translate and it fits in the method below.
 */
public final class JpaSecurityEventRepository implements SecurityEventRepository {

    private final SpringDataSecurityEventRepository securityEvents;

    public JpaSecurityEventRepository(SpringDataSecurityEventRepository securityEvents) {
        this.securityEvents = securityEvents;
    }

    @Override
    public void save(SecurityEvent securityEvent) {
        securityEvents.save(
            new SecurityEventJpaEntity(
                securityEvent.eventId(),
                securityEvent.type().name(),
                securityEvent.actor(),
                securityEvent.ipHash(),
                securityEvent.occurredAt()
            )
        );
    }
}
