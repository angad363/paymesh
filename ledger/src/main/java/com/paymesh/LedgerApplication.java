package com.paymesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The ledger service, standalone (ADR-050). Rooted at {@code com.paymesh} like every extracted
 * deployable, because {@code com.paymesh.shared} is a sibling of {@code com.paymesh.ledger}.
 */
@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
