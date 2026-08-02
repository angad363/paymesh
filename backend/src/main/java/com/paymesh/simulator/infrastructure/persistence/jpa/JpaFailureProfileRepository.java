package com.paymesh.simulator.infrastructure.persistence.jpa;

import com.paymesh.simulator.application.FailureProfileRepository;
import com.paymesh.simulator.domain.FailureProfile;

/**
 * PostgreSQL-backed implementation of the {@link FailureProfileRepository} port.
 * <p>
 * The row is seeded by V13, so its absence is a corrupt database and this adapter says so loudly
 * rather than inventing a default. A silent fallback here would make a failed migration look like a
 * working simulator that simply never declined anything.
 */
public final class JpaFailureProfileRepository implements FailureProfileRepository {

    private final SpringDataFailureProfileRepository profiles;

    public JpaFailureProfileRepository(SpringDataFailureProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Override
    public FailureProfile get() {
        return profiles.findById(FailureProfile.PROFILE_ID)
            .map(SimulatorJpaMapper::toDomain)
            .orElseThrow(() -> new IllegalStateException(
                "provider_failure_profile has no '" + FailureProfile.PROFILE_ID
                    + "' row; V13 seeds it, so this database did not migrate cleanly"
            ));
    }

    @Override
    public FailureProfile save(FailureProfile profile) {
        profiles.save(SimulatorJpaMapper.toEntity(profile));
        return profile;
    }
}
