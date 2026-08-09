package com.paymesh.risk.infrastructure.config;

import com.paymesh.risk.application.DenylistRepository;
import com.paymesh.risk.application.EvaluateRiskService;
import com.paymesh.risk.application.PaymentVelocityLookup;
import com.paymesh.risk.application.RiskAssessmentRepository;
import com.paymesh.risk.infrastructure.payment.PaymentModuleVelocityLookup;
import com.paymesh.risk.infrastructure.payment.SpringDataPaymentIntentCounter;
import com.paymesh.risk.infrastructure.persistence.jpa.JpaDenylistRepository;
import com.paymesh.risk.infrastructure.persistence.jpa.JpaRiskAssessmentRepository;
import com.paymesh.risk.infrastructure.persistence.jpa.SpringDataDenylistRepository;
import com.paymesh.risk.infrastructure.persistence.jpa.SpringDataRiskAssessmentRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Risk's beans, wired by hand (ADR-002, java-coding-conventions §13). Nothing in
 * {@code risk.application} or {@code risk.domain} carries a Spring annotation, so every one of them
 * is an ordinary object a plain JUnit test can construct -- which is why
 * {@code EvaluateRiskServiceTest} needs no context.
 */
@Configuration
@EnableConfigurationProperties(RiskProperties.class)
public class RiskConfiguration {

    @Bean
    RiskAssessmentRepository riskAssessmentRepository(
        SpringDataRiskAssessmentRepository assessments
    ) {
        return new JpaRiskAssessmentRepository(assessments);
    }

    @Bean
    DenylistRepository denylistRepository(SpringDataDenylistRepository entries) {
        return new JpaDenylistRepository(entries);
    }

    @Bean
    PaymentVelocityLookup paymentVelocityLookup(SpringDataPaymentIntentCounter intents) {
        return new PaymentModuleVelocityLookup(intents);
    }

    @Bean
    EvaluateRiskService evaluateRiskService(
        RiskAssessmentRepository assessments,
        DenylistRepository denylist,
        PaymentVelocityLookup velocity,
        RiskProperties properties,
        Clock clock
    ) {
        return new EvaluateRiskService(
            assessments, denylist, velocity, properties.velocityWindow(), clock
        );
    }
}
