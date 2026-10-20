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

import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class MerchantConfigurationTest {

    private final ApplicationContext context;
    private final Clock clock;
    private final MerchantRepository merchantRepository;
    private final RegisterMerchantService registerMerchantService;

    @Autowired
    MerchantConfigurationTest(
        ApplicationContext context,
        Clock clock,
        MerchantRepository merchantRepository,
        RegisterMerchantService registerMerchantService
    ) {
        this.context = context;
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

    /** Fails if {@code @EnableScheduling} leaves MerchantConfiguration: the outbox relay would never fire. */
    @Test
    void schedulingIsEnabledSoTheOutboxRelayRuns() {
        assertFalse(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
    }
}
