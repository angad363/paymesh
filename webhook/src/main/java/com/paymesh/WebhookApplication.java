package com.paymesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The webhook delivery service, standalone (ADR-042). Rooted at {@code com.paymesh}, like
 * {@code BackendApplication} -- NOT at {@code com.paymesh.webhook}, unlike
 * {@code ProviderSimApplication}. The difference is deliberate: provider-sim renamed its one shared
 * dependency into its own package (ADR-041), so scanning {@code com.paymesh.simulator.**} alone was
 * enough. Webhook's shared subtree keeps its ORIGINAL package name, {@code com.paymesh.shared}
 * (ADR-042 section 1) -- a sibling of {@code com.paymesh.webhook}, not a child of it -- so component
 * scanning has to start one level up to see both.
 */
@SpringBootApplication
public class WebhookApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebhookApplication.class, args);
    }
}
