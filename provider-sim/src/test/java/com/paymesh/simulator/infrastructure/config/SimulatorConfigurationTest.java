package com.paymesh.simulator.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.simulator.application.CallbackBodyWriter;
import com.paymesh.simulator.application.CallbackSender;
import com.paymesh.simulator.application.CaptureSimulatedPaymentService;
import com.paymesh.simulator.application.ConfigureFailureProfileService;
import com.paymesh.simulator.application.CreateSimulatedPaymentService;
import com.paymesh.simulator.application.CreateSimulatedRefundService;
import com.paymesh.simulator.application.DispatchProviderCallbacksService;
import com.paymesh.simulator.application.ExportReconciliationService;
import com.paymesh.simulator.application.FailureProfileRepository;
import com.paymesh.simulator.application.OutboundCallbackRepository;
import com.paymesh.simulator.application.SimulatedPaymentRepository;
import com.paymesh.simulator.application.SimulatedRefundRepository;
import com.paymesh.simulator.domain.SimulatedBehaviour;
import com.paymesh.simulator.infrastructure.http.HttpCallbackSender;
import com.paymesh.simulator.infrastructure.http.JacksonCallbackBodyWriter;
import com.paymesh.simulator.infrastructure.persistence.jpa.JpaFailureProfileRepository;
import com.paymesh.simulator.infrastructure.persistence.jpa.JpaOutboundCallbackRepository;
import com.paymesh.simulator.infrastructure.persistence.jpa.JpaSimulatedPaymentRepository;
import com.paymesh.simulator.infrastructure.persistence.jpa.JpaSimulatedRefundRepository;
import com.paymesh.simulator.infrastructure.schedule.SimulatorCallbackDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beans are wired by hand rather than component-scanned, so "is it wired" is a real question and this
 * is where it is answered. A missing {@code @Bean} method is otherwise a startup failure that only
 * shows up in whichever test happens to boot a context first.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class SimulatorConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresEveryRepositoryAdapter() {
        assertThat(context.getBean(SimulatedPaymentRepository.class))
            .isInstanceOf(JpaSimulatedPaymentRepository.class);
        assertThat(context.getBean(SimulatedRefundRepository.class))
            .isInstanceOf(JpaSimulatedRefundRepository.class);
        assertThat(context.getBean(OutboundCallbackRepository.class))
            .isInstanceOf(JpaOutboundCallbackRepository.class);
        assertThat(context.getBean(FailureProfileRepository.class))
            .isInstanceOf(JpaFailureProfileRepository.class);
    }

    @Test
    void wiresEveryUseCaseService() {
        assertThat(context.getBean(CreateSimulatedPaymentService.class)).isNotNull();
        assertThat(context.getBean(CaptureSimulatedPaymentService.class)).isNotNull();
        assertThat(context.getBean(CreateSimulatedRefundService.class)).isNotNull();
        assertThat(context.getBean(ExportReconciliationService.class)).isNotNull();
        assertThat(context.getBean(ConfigureFailureProfileService.class)).isNotNull();
    }

    @Test
    void wiresTheOutboundCollaborators() {
        assertThat(context.getBean(CallbackBodyWriter.class))
            .isInstanceOf(JacksonCallbackBodyWriter.class);
        assertThat(context.getBean(CallbackSender.class))
            .isInstanceOf(HttpCallbackSender.class);
    }

    /**
     * The service exists whatever the timer does, which is what lets every other test in this module
     * call {@code dispatch()} directly instead of waiting on a scheduler.
     */
    @Test
    void wiresTheDispatchServiceIndependentlyOfTheTimer() {
        assertThat(context.getBean(DispatchProviderCallbacksService.class)).isNotNull();
    }

    /**
     * THE ASSERTION THIS CLASS EXISTS FOR.
     * <p>
     * {@code dev} is the profile every {@code @SpringBootTest} in this project runs under. A
     * dispatcher timer alive in that context would POST signed callbacks at PayMesh while other
     * tests assert on the very payment intents it is mutating -- a flake that fails rarely, on
     * someone else's change, and cannot be reproduced. {@code @ConditionalOnProperty} means the bean
     * is ABSENT rather than present-and-idle, so this is a bean-count assertion and not a flag check.
     */
    @Test
    void doesNotRegisterTheCallbackTimerUnderTheDevelopmentProfile() {
        assertThat(context.getBeanNamesForType(SimulatorCallbackDispatcher.class))
            .as("paymesh.simulator.dispatch.enabled is false in application-dev.yaml")
            .isEmpty();
    }

    @Test
    void bindsTheSimulatorPropertiesFromTheDevelopmentProfile() {
        SimulatorProperties properties = context.getBean(SimulatorProperties.class);

        assertThat(properties.apiKey()).isNotBlank();
        assertThat(properties.callbackUrl()).contains("/internal/v1/provider-callbacks");
    }

    @Test
    void bindsTheDispatchTuning() {
        SimulatorDispatchProperties properties = context.getBean(SimulatorDispatchProperties.class);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.batchSize()).isPositive();
        assertThat(properties.maxAttempts()).isPositive();
        assertThat(properties.retryDelay()).isPositive();
        assertThat(properties.readTimeout()).isPositive();
    }

    /**
     * V13 seeds the singleton row, so no code path has to handle its absence. If the seed were ever
     * dropped, every create would fail on a missing ambient default instead of here.
     */
    @Test
    void findsTheFailureProfileRowTheMigrationSeeded() {
        assertThat(context.getBean(ConfigureFailureProfileService.class).get())
            .isNotNull()
            .satisfies(profile -> {
                assertThat(profile.defaultBehaviour()).isInstanceOf(SimulatedBehaviour.class);
                assertThat(profile.callbackDelay()).isGreaterThanOrEqualTo(Duration.ZERO);
            });
    }
}
