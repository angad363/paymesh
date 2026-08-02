package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.FailureProfile;
import com.paymesh.simulator.domain.SimulatedBehaviour;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads and replaces the one failure-injection row (SDD 13.1).
 *
 * <h2>Not idempotency-keyed, deliberately</h2>
 *
 * This is last-write-wins configuration, and a replayed configuration change is the same
 * configuration. A key would protect against nothing.
 *
 * <h2>The profile is ambient; a token still wins</h2>
 *
 * {@code defaultBehaviour} applies only to a payment whose token names no behaviour of its own -- see
 * {@link SimulatedBehaviour}. Setting it to DECLINE does not retroactively decline anything either:
 * the behaviour is resolved once at create time and frozen on the payment row, because a real
 * provider does not go back on something it has authorized.
 */
public final class ConfigureFailureProfileService {

    private final FailureProfileRepository profiles;
    private final Clock clock;

    public ConfigureFailureProfileService(FailureProfileRepository profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    public FailureProfile get() {
        return profiles.get();
    }

    public FailureProfile configure(SimulatedBehaviour defaultBehaviour, Duration callbackDelay) {
        return profiles.save(new FailureProfile(
            defaultBehaviour, callbackDelay, Instant.now(clock)
        ));
    }
}
