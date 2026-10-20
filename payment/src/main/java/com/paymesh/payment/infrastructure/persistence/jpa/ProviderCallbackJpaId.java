package com.paymesh.payment.infrastructure.persistence.jpa;

import java.io.Serializable;
import java.util.Objects;

/**
 * The composite primary key of provider_callbacks: {@code (provider, external_event_id)}.
 * <p>
 * <b>Deliberately not merchant-leading. See V10's comment before changing it.</b> A provider's event
 * id is provider-global and the merchant is derived from the intent the callback names, so adding
 * merchant_id here would let one event be processed once per merchant it resolves against -- the
 * exact duplicate the key exists to prevent.
 */
public class ProviderCallbackJpaId implements Serializable {

    private String provider;
    private String externalEventId;

    /** Required by JPA. Not for application use. */
    protected ProviderCallbackJpaId() {
    }

    public ProviderCallbackJpaId(String provider, String externalEventId) {
        this.provider = provider;
        this.externalEventId = externalEventId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProviderCallbackJpaId id
            && Objects.equals(provider, id.provider)
            && Objects.equals(externalEventId, id.externalEventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, externalEventId);
    }
}
