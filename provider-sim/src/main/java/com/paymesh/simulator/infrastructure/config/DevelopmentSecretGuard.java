package com.paymesh.simulator.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Refuses to let the application start on the committed development value of
 * {@code paymesh.simulator.api-key}, this module's own copy of the check the monolith's
 * {@code DevelopmentSecretGuard} (ADR-041) is documented as making on this side of the door.
 * <p>
 * {@code @NotBlank} on {@link SimulatorProperties} is not enough on its own: it is satisfied by the
 * exact string committed to {@code application-dev.yaml}, which is public. An operator who copies
 * that value into a real environment variable -- or whose profiles resolve to {@code dev,production}
 * instead of {@code dev} alone -- would pass validation while achieving nothing. This is the one
 * secret this deployable has: it is the ONLY authentication on {@code /sim/v1/**}, and
 * {@code POST /sim/v1/payments} queues a callback that marks a PayMesh payment SUCCEEDED, so a
 * published value lets anyone collect any payment on the platform without ever forging a signature.
 */
@Configuration(proxyBeanMethods = false)
public class DevelopmentSecretGuard {

    private static final String DEV_PROFILE = "dev";

    private static final String PROPERTY = "paymesh.simulator.api-key";
    private static final String COMMITTED_DEV_VALUE = "dev-only-insecure-simulator-api-key-change-me";
    private static final String ENVIRONMENT_VARIABLE = "PAYMESH_SIMULATOR_API_KEY";

    public DevelopmentSecretGuard(Environment environment) {
        if (isDevelopmentOnly(environment)) {
            return;
        }

        String configured = environment.getProperty(PROPERTY);

        if (configured != null && COMMITTED_DEV_VALUE.equalsIgnoreCase(configured.strip())) {
            throw new IllegalStateException(
                PROPERTY + " is set to the development value committed to this repository, which "
                    + "is public and therefore is the only authentication on /sim/v1/**, and POST "
                    + "/sim/v1/payments queues a callback that marks a payment SUCCEEDED -- so anyone "
                    + "could collect any payment on the platform without ever forging a signature. "
                    + "Set " + ENVIRONMENT_VARIABLE + " to a private random value of at least 32 "
                    + "bytes, or activate the 'dev' profile if this is a development machine."
            );
        }
    }

    /**
     * "dev" has to be the only active profile, not merely one of them -- the same reasoning the
     * monolith's guard uses, for the same reason: {@code dev,production} is an ordinary operator
     * slip, and it produces the worst instance imaginable, a real deployment running the throwaway
     * key published in this repository.
     */
    private static boolean isDevelopmentOnly(Environment environment) {
        String[] activeProfiles = environment.getActiveProfiles();

        return activeProfiles.length == 1 && DEV_PROFILE.equals(activeProfiles[0]);
    }
}
