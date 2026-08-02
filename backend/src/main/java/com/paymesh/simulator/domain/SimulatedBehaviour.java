package com.paymesh.simulator.domain;

import java.util.Locale;
import java.util.Map;

/**
 * What a simulated payment will do, and the deterministic test token that asks for it (SDD 13.6).
 *
 * <h2>The token wins; the profile is a fallback</h2>
 *
 * SDD 13.6 asks for <b>deterministic test tokens</b>. SDD 13.1 asks for <b>timeout and error
 * percentages</b>. Those pull in opposite directions and this enum is the resolution: a recognised
 * token names its behaviour exactly, and only a token that names nothing falls through to
 * {@code provider_failure_profile.default_behaviour}. A probabilistic branch in a suite that runs on
 * every commit is a flake generator, and the thing percentages are actually reached for -- "make
 * everything decline for a while" -- is the ambient default at its limit.
 *
 * <h2>Resolved once, then frozen</h2>
 *
 * {@code provider_payments.behaviour} stores the answer, so changing the failure profile mid-flight
 * cannot make a payment already in progress change its mind. A real provider does not retroactively
 * decline something it has authorized.
 */
public enum SimulatedBehaviour {

    /**
     * The happy path. AUTOMATIC capture emits one SUCCEEDED; MANUAL emits one AUTHORIZED and waits
     * for {@code POST /sim/v1/payments/{id}/capture}.
     */
    SUCCEED,

    /** One FAILED callback carrying {@code do_not_honour}. */
    DECLINE,

    /** One REQUIRES_ACTION callback carrying an {@code actionUrl} with a token in its query. */
    REQUIRE_ACTION,

    /**
     * THE LOST CALLBACK. The payment is recorded {@code TIMED_OUT} at the provider and <b>no
     * outbound callback row is written at all</b>.
     * <p>
     * This is the only behaviour that produces nothing, and it is the most valuable one: a PayMesh
     * intent left in PROCESSING with no callback coming is the exact state ADR-015's timeout sweeper
     * exists for, and until now there was no way to reach it except by hand.
     */
    TIMEOUT,

    /**
     * THE DUPLICATE CALLBACK. Two rows sharing one {@code external_event_id} and one body. PayMesh
     * answers APPLIED then DUPLICATE, and applies the payment once (ADR-012 section 1).
     */
    DUPLICATE_CALLBACK,

    /**
     * THE OUT-OF-ORDER CALLBACK. Two rows with distinct event ids, the second stamped with an
     * <b>earlier</b> {@code occurred_at} than the first.
     * <p>
     * PayMesh judges staleness BEFORE the state machine, so the second is refused as
     * {@code IGNORED_STALE} rather than {@code IGNORED_TERMINAL} -- see
     * {@code RecordProviderCallbackService.judge}. That ordering is what makes this reproducible
     * from outside without a merchant action in between, and it is why the pair is two SUCCEEDEDs
     * rather than something more elaborate.
     */
    STALE_CALLBACK;

    /**
     * The tokens, and nothing that resembles an instrument.
     * <p>
     * SDD 4.2 puts real cardholder data out of scope for the entire project, and a simulator is the
     * one component where someone might reasonably think a real PAN was harmless. These are the only
     * values this module ever recognises; anything else falls through to the ambient default rather
     * than being interpreted.
     */
    private static final Map<String, SimulatedBehaviour> BY_TOKEN = Map.of(
        "tok_sim_success", SUCCEED,
        "tok_sim_decline", DECLINE,
        "tok_sim_3ds", REQUIRE_ACTION,
        "tok_sim_timeout", TIMEOUT,
        "tok_sim_duplicate", DUPLICATE_CALLBACK,
        "tok_sim_stale", STALE_CALLBACK
    );

    /**
     * The token's behaviour, or the ambient default when the token names none.
     * <p>
     * An unrecognised token is <b>not</b> an error. A merchant integration under test sends whatever
     * token its own fixtures use, and refusing it would make the simulator harder to point at than a
     * real provider -- which accepts any test instrument and decides for itself. The failure profile
     * is what such a caller is really configuring.
     */
    public static SimulatedBehaviour resolve(String token, SimulatedBehaviour ambientDefault) {
        if (ambientDefault == null) {
            throw new IllegalArgumentException("An ambient default behaviour is required");
        }

        if (token == null || token.isBlank()) {
            return ambientDefault;
        }

        return BY_TOKEN.getOrDefault(token.trim().toLowerCase(Locale.ROOT), ambientDefault);
    }

    /** Parses the wire value of a failure profile update. */
    public static SimulatedBehaviour parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Simulated behaviour cannot be blank");
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            // valueOf's own message names the Java enum class, and an error body must not hand a
            // caller internal type names.
            throw new IllegalArgumentException("Unknown simulated behaviour: " + value);
        }
    }
}
