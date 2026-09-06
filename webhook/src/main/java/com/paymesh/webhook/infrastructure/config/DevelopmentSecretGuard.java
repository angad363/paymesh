package com.paymesh.webhook.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Refuses to let the application start on the committed development value of
 * {@code paymesh.webhook.master-key}, this module's own copy of the check the monolith's
 * {@code DevelopmentSecretGuard} made before this capability was extracted (ADR-042), the same shape
 * {@code provider-sim}'s own guard made for {@code paymesh.simulator.api-key} (ADR-041).
 * <p>
 * {@code @NotBlank} on {@link WebhookProperties} is not enough on its own: it is satisfied by the
 * exact string committed to {@code application-dev.yaml}, which is public. An operator who copies
 * that value into a real environment variable -- or whose profiles resolve to {@code dev,production}
 * instead of {@code dev} alone -- would pass validation while achieving nothing. This is the one
 * secret this deployable has: every merchant's signing secret derives from it (ADR-028 section 2),
 * so a published master key lets anyone forge a delivery to every merchant's endpoint at once.
 */
@Configuration(proxyBeanMethods = false)
public class DevelopmentSecretGuard {

    private static final String DEV_PROFILE = "dev";

    private static final String PROPERTY = "paymesh.webhook.master-key";
    private static final String COMMITTED_DEV_VALUE = "dev-only-insecure-webhook-master-key-change-me";
    private static final String ENVIRONMENT_VARIABLE = "PAYMESH_WEBHOOK_MASTER_KEY";

    public DevelopmentSecretGuard(Environment environment) {
        if (isDevelopmentOnly(environment)) {
            return;
        }

        String configured = environment.getProperty(PROPERTY);

        if (configured != null && COMMITTED_DEV_VALUE.equalsIgnoreCase(configured.strip())) {
            throw new IllegalStateException(
                PROPERTY + " is set to the development value committed to this repository, which "
                    + "is public, and every merchant's webhook signing secret derives from it -- so "
                    + "anyone reading this file could forge a delivery to every merchant's endpoint "
                    + "at once. Set " + ENVIRONMENT_VARIABLE + " to a private random value of at "
                    + "least 32 bytes, or activate the 'dev' profile if this is a development "
                    + "machine."
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
