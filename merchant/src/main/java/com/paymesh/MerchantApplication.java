package com.paymesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The merchant service, standalone (ADR-046). Rooted at {@code com.paymesh} like every extracted
 * deployable, because {@code com.paymesh.shared} is a sibling of {@code com.paymesh.merchant}.
 */
@SpringBootApplication
public class MerchantApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerchantApplication.class, args);
    }
}
