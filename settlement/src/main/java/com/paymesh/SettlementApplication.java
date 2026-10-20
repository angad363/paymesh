package com.paymesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The settlement service, standalone (ADR-047). Rooted at {@code com.paymesh} like every extracted
 * deployable, because {@code com.paymesh.shared} is a sibling of {@code com.paymesh.settlement}.
 */
@SpringBootApplication
public class SettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementApplication.class, args);
    }
}
