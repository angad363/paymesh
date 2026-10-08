package com.paymesh.risk.infrastructure.config;

import com.paymesh.risk.application.DenylistRepository;
import com.paymesh.risk.application.EvaluateRiskService;
import com.paymesh.risk.application.PaymentVelocityLookup;
import com.paymesh.risk.application.RiskAssessmentRepository;
import com.paymesh.risk.infrastructure.events.PaymentCreatedProjector;
import com.paymesh.risk.infrastructure.persistence.jpa.JpaDenylistRepository;
import com.paymesh.risk.infrastructure.persistence.jpa.JpaRiskAssessmentRepository;
import com.paymesh.risk.infrastructure.persistence.jpa.SpringDataDenylistRepository;
import com.paymesh.risk.infrastructure.persistence.jpa.SpringDataRiskAssessmentRepository;
import com.paymesh.risk.infrastructure.read.PaymentIntentRefStore;
import com.paymesh.risk.infrastructure.security.RiskEvaluationKeyFilter;
import com.paymesh.shared.outbox.application.EventHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Risk's beans, wired by hand (ADR-002, java-coding-conventions §13). Nothing in
 * {@code risk.application} or {@code risk.domain} carries a Spring annotation, so every one of them
 * is an ordinary object a plain JUnit test can construct.
 * <p>
 * {@code @EnableScheduling} lives here rather than in a platform configuration -- this deployable
 * has exactly one capability, the same call {@code WebhookConfiguration} and
 * {@code SimulatorConfiguration} made (ADR-041/042).
 * <p>
 * THE ONE THING THAT CHANGED FROM THE MONOLITH'S COPY OF THIS CLASS: {@link PaymentVelocityLookup}
 * is answered by {@link PaymentIntentRefStore}, the event-fed local read model (ADR-044 section 4),
 * not {@code PaymentModuleVelocityLookup} -- there is no {@code GetPaymentIntentService} to call
 * in-process here, Payment is a separate deployable now.
 */
@Configuration
@EnableConfigurationProperties(RiskProperties.class)
@EnableScheduling
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

    /**
     * ONE BEAN, TWO INTERFACES. {@link PaymentIntentRefStore} implements
     * {@link PaymentVelocityLookup} directly rather than sitting behind a separate wrapper bean --
     * a second {@code @Bean} method merely re-exposing this instance under the interface type
     * would register a SECOND bean definition assignable to {@code PaymentVelocityLookup}
     * ({@code paymentIntentRefStore} itself already qualifies, since it implements the interface),
     * making {@link #evaluateRiskService}'s injection point ambiguous despite both resolving to the
     * same object. {@code EvaluateRiskService} asks for {@code PaymentVelocityLookup} and Spring
     * resolves it to this bean because it is the only one there is.
     */
    @Bean
    PaymentIntentRefStore paymentIntentRefStore(DataSource dataSource) {
        return new PaymentIntentRefStore(new JdbcTemplate(dataSource));
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

    /**
     * Feeds {@code payment_intent_ref} from Payment's own {@code payment.created} events. One bean,
     * registered as an {@link EventHandler} the same way every other consumer in this codebase is --
     * {@link com.paymesh.shared.outbox.application.EventDispatcher} collects every such bean and
     * indexes it by event type.
     */
    @Bean
    EventHandler paymentCreatedProjector(PaymentIntentRefStore store) {
        return new PaymentCreatedProjector(store);
    }

    /**
     * THE AUTHENTICATION FOR {@code /internal/v1/risk-evaluations}. There is no other.
     * <p>
     * No order is set, same reason {@code SimulatorApiKeyFilter}'s registration gives: this
     * deployable carries no Spring Security chain at all, so there is nothing left to order it
     * relative to.
     */
    @Bean
    FilterRegistrationBean<RiskEvaluationKeyFilter> riskEvaluationKeyFilterRegistration(
        RiskProperties properties, ObjectMapper objectMapper
    ) {
        return new FilterRegistrationBean<>(
            new RiskEvaluationKeyFilter(properties.evaluateKey(), objectMapper)
        );
    }
}
