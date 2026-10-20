package com.paymesh.refund.infrastructure.persistence.jpa;

import java.io.Serializable;
import java.util.Objects;

/** The composite key of {@code refund_callbacks}: {@code (provider, external_event_id)}. */
public class RefundCallbackJpaId implements Serializable {

    private String provider;
    private String externalEventId;

    protected RefundCallbackJpaId() {
    }

    public RefundCallbackJpaId(String provider, String externalEventId) {
        this.provider = provider;
        this.externalEventId = externalEventId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RefundCallbackJpaId id
            && Objects.equals(provider, id.provider)
            && Objects.equals(externalEventId, id.externalEventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, externalEventId);
    }
}
