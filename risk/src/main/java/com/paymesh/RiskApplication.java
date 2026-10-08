package com.paymesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The risk evaluation service, standalone (ADR-044). Rooted at {@code com.paymesh}, like {@code
 * BackendApplication} and {@code WebhookApplication} -- NOT at {@code com.paymesh.risk}. The
 * reason is the same one {@code WebhookApplication}'s javadoc gives: this deployable's shared
 * subtree keeps its ORIGINAL package name, {@code com.paymesh.shared} (a sibling of {@code
 * com.paymesh.risk}, not a child of it), so component scanning has to start one level up to see
 * both. Provider-sim could root its main class inside its own capability package because it
 * renamed its one shared dependency; risk does not repeat that rename any more than webhook or
 * engagement did.
 */
@SpringBootApplication
public class RiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskApplication.class, args);
    }
}
