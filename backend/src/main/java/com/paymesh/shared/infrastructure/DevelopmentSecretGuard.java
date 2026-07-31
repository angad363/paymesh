package com.paymesh.shared.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Refuses to let the application start on a secret that is published in this repository.
 * <p>
 * Requiring the property to be set is not enough on its own. The development secret is public, so
 * an operator who copies it out of application-dev.yaml into a real environment variable satisfies
 * that requirement while achieving nothing: every access token is then signed with a value anyone
 * can read, and anyone can mint a token for any user at any merchant. The failure is silent, which
 * is what makes it worth a startup check.
 * <p>
 * This lives in {@code shared} rather than in the identity module because it is a deployment rule,
 * not an identity rule -- it says where a value may come from, not what the value means. It reads
 * the property from the Environment for the same reason: the rule should not need to know which
 * module happens to consume the secret.
 */
@Configuration(proxyBeanMethods = false)
public class DevelopmentSecretGuard {

    private static final String DEV_PROFILE = "dev";
    private static final String JWT_SECRET_PROPERTY = "paymesh.security.jwt.secret";

    /**
     * Duplicated from application-dev.yaml on purpose. Reading the dev file at runtime to learn
     * what to reject would mean the check silently stops working the day that file is not on the
     * classpath -- exactly the deployment where it matters most.
     */
    private static final String DEV_JWT_SECRET = "dev-only-insecure-jwt-signing-secret-change-me";

    public DevelopmentSecretGuard(Environment environment) {
        if (isDevelopmentOnly(environment)) {
            return;
        }

        if (isCommittedDevSecret(environment.getProperty(JWT_SECRET_PROPERTY))) {
            throw new IllegalStateException(
                JWT_SECRET_PROPERTY + " is set to the development value committed to this "
                    + "repository, which is public and therefore signs tokens anyone can forge. "
                    + "Set PAYMESH_SECURITY_JWT_SECRET to a private random value of at least 32 "
                    + "bytes, or activate the 'dev' profile if this is a development machine."
            );
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

    /**
     * Compared loosely on purpose. Spring does not trim environment variables, and a Kubernetes
     * secret populated from a file or a Docker --env-file routinely carries a trailing newline;
     * an exact match would then wave through a signing key that is a public string plus one
     * guessable character. Anything that differs from the committed value only by whitespace or
     * case is that value for every purpose an attacker cares about.
     */
    private static boolean isCommittedDevSecret(String configuredSecret) {
        return configuredSecret != null && DEV_JWT_SECRET.equalsIgnoreCase(configuredSecret.strip());
    }
}
