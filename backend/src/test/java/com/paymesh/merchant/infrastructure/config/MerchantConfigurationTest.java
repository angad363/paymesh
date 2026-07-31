package com.paymesh.merchant.infrastructure.config;

import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.merchant.infrastructure.persistence.jpa.JpaMerchantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import com.paymesh.TestcontainersConfiguration;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class MerchantConfigurationTest {

    private final Clock clock;
    private final MerchantRepository merchantRepository;
    private final RegisterMerchantService registerMerchantService;

    @Autowired
    MerchantConfigurationTest(
        Clock clock,
        MerchantRepository merchantRepository,
        RegisterMerchantService registerMerchantService
    ) {
        this.clock = clock;
        this.merchantRepository = merchantRepository;
        this.registerMerchantService = registerMerchantService;
    }

    @Test
    void providesMerchantApplicationBeans() {
        assertNotNull(clock);
        assertNotNull(registerMerchantService);

        assertInstanceOf(
            JpaMerchantRepository.class,
            merchantRepository
        );
    }
}
