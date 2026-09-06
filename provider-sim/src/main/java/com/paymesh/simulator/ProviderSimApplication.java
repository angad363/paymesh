package com.paymesh.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The provider simulator, standalone (ADR-041). Package-root main class, same convention as
 * {@code BackendApplication}: {@code @SpringBootApplication} scans {@code com.paymesh.simulator.**}
 * and nothing else, because there is nothing else in this deployable.
 */
@SpringBootApplication
public class ProviderSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderSimApplication.class, args);
    }
}
