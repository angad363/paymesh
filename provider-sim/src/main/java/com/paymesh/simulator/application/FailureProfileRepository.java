package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.FailureProfile;

/**
 * The port the single failure-profile row answers.
 * <p>
 * {@link #get()} returns a value rather than an {@code Optional} because V13 seeds the row, so its
 * absence is a corrupt database and not a case to handle. An {@code Optional} here would push a
 * defensive default into every caller and quietly make "the profile is missing" a supported state.
 */
public interface FailureProfileRepository {

    FailureProfile get();

    FailureProfile save(FailureProfile profile);
}
