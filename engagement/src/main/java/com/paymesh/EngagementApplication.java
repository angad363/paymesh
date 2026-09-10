package com.paymesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The engagement service, standalone (ADR-043) -- Notification, Reporting and Audit, moved out
 * together. Rooted at {@code com.paymesh}, like {@code BackendApplication} and
 * {@code WebhookApplication} -- NOT at {@code com.paymesh.engagement}, because there is no such
 * package: this deployable carries three sibling capability packages
 * ({@code com.paymesh.notification}, {@code com.paymesh.reporting}, {@code com.paymesh.audit}) plus
 * {@code com.paymesh.shared}, and component scanning has to start one level up to see all four.
 */
@SpringBootApplication
public class EngagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngagementApplication.class, args);
    }
}
