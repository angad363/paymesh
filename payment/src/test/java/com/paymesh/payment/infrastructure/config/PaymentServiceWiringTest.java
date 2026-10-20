package com.paymesh.payment.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.infrastructure.security.ApiCredentialAuthenticator;
import com.paymesh.payment.application.RiskCheck;
import com.paymesh.payment.infrastructure.risk.RiskServiceHttpCheck;
import com.paymesh.shared.security.ApiKeyAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** What makes this deployable the payment SERVICE rather than a copy of the monolith (ADR-048). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class PaymentServiceWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RiskCheck riskCheck;

    @Autowired
    private ApiKeyAuthenticator apiKeyAuthenticator;

    /** Fails if {@code @EnableScheduling} leaves this deployable's configuration: no relay, no sweeper, ever. */
    @Test
    void schedulingIsEnabledSoTheRelayAndTheSweepersCanRun() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
    }

    /** ADR-048 section 3: the in-process adapter needs the monolith's risk classes; only HTTP exists here. */
    @Test
    void riskIsReachedOverHttpOnly() {
        assertThat(riskCheck).isInstanceOf(RiskServiceHttpCheck.class);
    }

    /** ADR-048 section 4: the merchant service's authenticator, over the local projection. */
    @Test
    void apiKeysAreVerifiedByTheMerchantAuthenticatorOverTheLocalProjection() {
        assertThat(apiKeyAuthenticator).isInstanceOf(ApiCredentialAuthenticator.class);
    }
}
