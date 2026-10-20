package com.paymesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The identity and access service, standalone (ADR-045) -- register/login/refresh/logout move out;
 * token validation stays distributed via the same shared HS256 secret every other deployable already
 * verifies with. Rooted at {@code com.paymesh}, like {@code BackendApplication} and
 * {@code RiskApplication} -- NOT at {@code com.paymesh.identity}, for the same reason every prior
 * extraction's main class gives: this deployable's shared subtree keeps its ORIGINAL package name,
 * {@code com.paymesh.shared} (a sibling of {@code com.paymesh.identity}, not a child of it), so
 * component scanning has to start one level up to see both.
 */
@SpringBootApplication
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
