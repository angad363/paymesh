package com.paymesh.simulator.domain;

/**
 * Which of PayMesh's callback routes a queued callback is aimed at.
 *
 * <p>A column on the row rather than something the dispatcher infers from the subject, because the
 * dispatcher must not have to know the shape of a callback to know where it goes. Mirrors
 * {@code ck_provider_outbound_callbacks_target}.
 *
 * <p>The simulator still holds no reference to PayMesh: this names a KIND of callback, and the URL
 * each kind resolves to is configuration in {@code infrastructure}, exactly like the payment
 * callback URL always was.
 */
public enum CallbackTarget {

    PAYMENT,
    PAYOUT
}
