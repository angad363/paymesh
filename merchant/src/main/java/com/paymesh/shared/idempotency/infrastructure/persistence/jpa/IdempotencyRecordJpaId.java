package com.paymesh.shared.idempotency.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

/**
 * The composite primary key of idempotency_records: merchant + endpoint template + key.
 * <p>
 * There is no surrogate id to map instead. The scope is the identity, and giving JPA a simpler
 * single-column key it could cache by would let two tenants' rows share a first-level cache entry.
 */
@Embeddable
public record IdempotencyRecordJpaId(

    @Column(name = "merchant_id", nullable = false, length = 40)
    String merchantId,

    @Column(name = "endpoint", nullable = false, length = 200)
    String endpoint,

    @Column(name = "idempotency_key", nullable = false, length = 255)
    String idempotencyKey

) implements Serializable {
}
