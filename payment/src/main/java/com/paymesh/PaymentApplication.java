package com.paymesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The payment service, standalone (ADR-048): customer, order, payment and refund as one deployable.
 * Rooted at {@code com.paymesh} like every extracted deployable, because {@code com.paymesh.shared}
 * is a sibling of the capability packages.
 */
@SpringBootApplication
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
