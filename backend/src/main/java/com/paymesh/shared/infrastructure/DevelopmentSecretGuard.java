package com.paymesh.shared.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Refuses to let the application start on a secret that is published in this repository.
 * <p>
 * Requiring the property to be set is not enough on its own. The development secrets are public, so
 * an operator who copies one out of application-dev.yaml into a real environment variable satisfies
 * that requirement while achieving nothing. The failure is silent, which is what makes it worth a
 * startup check.
 * <p>
 * <b>Four secrets now, and none of them is the lesser one.</b> The JWT key signs every access
 * token; the provider callback key is the ONLY authentication on the endpoint that moves payments to
 * SUCCEEDED; the refund callback key is the same for money going back out, which posts a ledger
 * reversal; and the reconciliation key is this application's own copy of the provider simulator's
 * shared key -- the caller's side of a credential that key's OWN guard now lives on the other side
 * of (ADR-041: {@code provider-sim} is a separate deployable with its own
 * {@code DevelopmentSecretGuard} for {@code paymesh.simulator.api-key}). A published value in any of
 * these means anyone can move money on this platform -- so the guard is a loop over a list rather
 * than one check with siblings bolted on, and the next secret is a line in {@link #GUARDED} plus a
 * case in {@code ReconciliationApiKeyStartupTest} and its siblings.
 * <p>
 * <b>The fifth points outward rather than inward, which is new.</b> The webhook master key derives
 * every merchant's signing secret (ADR-028 §2). Publishing it does not let an attacker move money
 * on PayMesh -- it lets them sign as PayMesh to people who are not on PayMesh, who have no way to
 * tell and every reason to act on it. The blast radius is every merchant at once, which is the cost
 * of one master key and is why per-endpoint rotation exists separately.
 * <p>
 * <b>The reconciliation key shares a VALUE with provider-sim's simulator key without sharing a
 * meaning, or a guard.</b> They are the same string today because the provider is the bundled
 * simulator -- one is what the provider expects, the other is what this application sends -- and
 * they stop being the same string the day the provider is external. Each deployable guards its own
 * copy, which is what makes that split a config change rather than a security regression.
 * <p>
 * This lives in {@code shared} rather than in a capability module because it is a deployment rule,
 * not a payment or identity rule -- it says where a value may come from, not what the value means.
 * It reads the properties from the Environment for the same reason: the rule should not need to know
 * which module happens to consume each secret.
 */
@Configuration(proxyBeanMethods = false)
public class DevelopmentSecretGuard {

    private static final String DEV_PROFILE = "dev";

    /**
     * Every secret with a committed development value, and the environment variable that overrides
     * it.
     * <p>
     * The dev values are duplicated from application-dev.yaml on purpose. Reading that file at
     * runtime to learn what to reject would mean the check silently stops working the day it is not
     * on the classpath -- exactly the deployment where it matters most.
     */
    private static final List<GuardedSecret> GUARDED = List.of(
        new GuardedSecret(
            "paymesh.security.jwt.secret",
            "dev-only-insecure-jwt-signing-secret-change-me",
            "PAYMESH_SECURITY_JWT_SECRET",
            "signs tokens anyone can forge"
        ),
        new GuardedSecret(
            "paymesh.provider.callback-secret",
            "dev-only-insecure-provider-callback-secret-change-me",
            "PAYMESH_PROVIDER_CALLBACK_SECRET",
            "is the only authentication on the endpoint that marks payments SUCCEEDED, so anyone "
                + "could forge a callback for any payment"
        ),
        new GuardedSecret(
            "paymesh.refund.callback-secret",
            "dev-only-insecure-refund-callback-secret-change-me",
            "PAYMESH_REFUND_CALLBACK_SECRET",
            "is the only authentication on the endpoint that marks refunds SUCCEEDED, which posts "
                + "a ledger reversal -- so anyone could forge money back out of any merchant's "
                + "balance"
        ),
        new GuardedSecret(
            "paymesh.webhook.master-key",
            "dev-only-insecure-webhook-master-key-change-me",
            "PAYMESH_WEBHOOK_MASTER_KEY",
            "derives EVERY merchant's webhook signing secret (ADR-028 section 2), so a published "
                + "value lets anyone sign as PayMesh to every merchant at once -- and unlike the "
                + "callback secrets, the merchants who would act on those forged events are "
                + "outside this platform entirely"
        ),
        new GuardedSecret(
            "paymesh.reconciliation.api-key",
            "dev-only-insecure-simulator-api-key-change-me",
            "PAYMESH_RECONCILIATION_API_KEY",
            "is the CALLER's copy of the provider's API key, so a published value hands over "
                + "whatever that key opens -- today the same /sim/v1/** access provider-sim's own "
                + "DevelopmentSecretGuard guards on the other side of the same door (ADR-041)"
        )
    );

    public DevelopmentSecretGuard(Environment environment) {
        if (isDevelopmentOnly(environment)) {
            return;
        }

        for (GuardedSecret guarded : GUARDED) {
            if (guarded.isCommittedDevValue(environment.getProperty(guarded.property()))) {
                throw new IllegalStateException(
                    guarded.property() + " is set to the development value committed to this "
                        + "repository, which is public and therefore " + guarded.consequence()
                        + ". Set " + guarded.environmentVariable() + " to a private random value of "
                        + "at least 32 bytes, or activate the 'dev' profile if this is a "
                        + "development machine."
                );
            }
        }
    }

    /**
     * "dev" has to be the only active profile, not merely one of them. Layered configuration
     * appends profiles rather than replacing them -- a Helm overlay on a base, a compose env_file
     * beside an inline value -- so {@code dev,production} is an ordinary operator slip, and it
     * produces the worst instance imaginable: real datasource credentials from the environment,
     * every token signed with a key published in this repository.
     */
    private static boolean isDevelopmentOnly(Environment environment) {
        String[] activeProfiles = environment.getActiveProfiles();

        return activeProfiles.length == 1 && DEV_PROFILE.equals(activeProfiles[0]);
    }

    private record GuardedSecret(
        String property,
        String developmentValue,
        String environmentVariable,
        String consequence
    ) {

        /**
         * Compared loosely on purpose. Spring does not trim environment variables, and a Kubernetes
         * secret populated from a file or a Docker --env-file routinely carries a trailing newline;
         * an exact match would then wave through a key that is a public string plus one guessable
         * character. Anything that differs from the committed value only by whitespace or case is
         * that value for every purpose an attacker cares about.
         */
        boolean isCommittedDevValue(String configured) {
            return configured != null && developmentValue.equalsIgnoreCase(configured.strip());
        }
    }
}
