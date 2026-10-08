package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

/**
 * The composite primary key of processed_events: which consumer, which event.
 * <p>
 * There is no surrogate id to map instead. The pair IS the identity -- one event is delivered to
 * every subscribed consumer, and each dedups independently -- and a single-column key on
 * {@code event_id} would let the first consumer to run silently starve every other one.
 */
@Embeddable
public record ProcessedEventJpaId(

    @Column(name = "consumer_name", nullable = false, length = 100)
    String consumerName,

    @Column(name = "event_id", nullable = false, length = 40)
    String eventId

) implements Serializable {
}
