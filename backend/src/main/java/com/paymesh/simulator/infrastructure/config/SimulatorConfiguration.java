package com.paymesh.simulator.infrastructure.config;

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
import com.paymesh.simulator.infrastructure.http.HttpCallbackSender;
import com.paymesh.simulator.infrastructure.http.JacksonCallbackBodyWriter;
import com.paymesh.simulator.infrastructure.persistence.jpa.JpaFailureProfileRepository;
import com.paymesh.simulator.infrastructure.persistence.jpa.JpaOutboundCallbackRepository;
import com.paymesh.simulator.infrastructure.persistence.jpa.JpaSimulatedPaymentRepository;
import com.paymesh.simulator.infrastructure.persistence.jpa.JpaSimulatedRefundRepository;
import com.paymesh.simulator.infrastructure.persistence.jpa.SpringDataFailureProfileRepository;
import com.paymesh.simulator.infrastructure.persistence.jpa.SpringDataOutboundCallbackRepository;
import com.paymesh.simulator.infrastructure.persistence.jpa.SpringDataSimulatedPaymentRepository;
import com.paymesh.simulator.infrastructure.persistence.jpa.SpringDataSimulatedRefundRepository;
import com.paymesh.simulator.infrastructure.schedule.SimulatorCallbackDispatcher;
import com.paymesh.simulator.infrastructure.security.SimulatorApiKeyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Explicit wiring for the provider simulator (no component scanning of application/domain classes).
 * The Clock, the ObjectMapper and the TransactionTemplate are injected, not declared:
 * {@code SharedConfiguration} owns them because every capability needs them and none owns them.
 *
 * <h2>NOTHING IN THIS FILE IMPORTS ANOTHER CAPABILITY, AND THAT IS THE MODULE'S WHOLE POINT</h2>
 *
 * In every other configuration class in this codebase, a cross-module dependency is visible here as
 * an import -- {@code PaymentConfiguration} names Order's services, which is exactly what the
 * {@code OrderLookup} port exists to make legible. This class names nobody, because SDD 13.2 says the
 * simulator does not own PayMesh state and SDD 13.6 wants it independently deployable. A single
 * imported type would make extracting it a rewrite instead of a move.
 * <p>
 * The one place that could have slipped is the callback signing secret, which the receiver also
 * reads. It is bound by {@link Value} from {@code paymesh.provider.callback-secret} rather than by
 * injecting Payment's {@code ProviderProperties} bean. A property name is a string the two sides
 * agree on -- the same kind of agreement as the JSON contract in {@code CallbackBody} -- whereas the
 * bean is a Java type from a package this module may not see. {@code ModuleBoundaryTest} would fail
 * on the import; it cannot fail on a string, so the reason is written here instead.
 */
@Configuration
@EnableConfigurationProperties({SimulatorProperties.class, SimulatorDispatchProperties.class})
public class SimulatorConfiguration {

    @Bean
    SimulatedPaymentRepository simulatedPaymentRepository(
        SpringDataSimulatedPaymentRepository springDataSimulatedPaymentRepository
    ) {
        return new JpaSimulatedPaymentRepository(springDataSimulatedPaymentRepository);
    }

    @Bean
    SimulatedRefundRepository simulatedRefundRepository(
        SpringDataSimulatedRefundRepository springDataSimulatedRefundRepository
    ) {
        return new JpaSimulatedRefundRepository(springDataSimulatedRefundRepository);
    }

    @Bean
    OutboundCallbackRepository outboundCallbackRepository(
        SpringDataOutboundCallbackRepository springDataOutboundCallbackRepository
    ) {
        return new JpaOutboundCallbackRepository(springDataOutboundCallbackRepository);
    }

    @Bean
    FailureProfileRepository failureProfileRepository(
        SpringDataFailureProfileRepository springDataFailureProfileRepository
    ) {
        return new JpaFailureProfileRepository(springDataFailureProfileRepository);
    }

    /**
     * Serializes a callback body to the exact string that will be stored, signed and sent. One
     * implementation, and it is behind an interface anyway, because the application layer enqueues
     * the bytes and must not know Jackson exists.
     */
    @Bean
    CallbackBodyWriter callbackBodyWriter(ObjectMapper objectMapper) {
        return new JacksonCallbackBodyWriter(objectMapper);
    }

    /**
     * The outbound HTTP client, and the seam that makes the delivery test possible.
     *
     * <h2>Why the read timeout is short and why it is here rather than a default</h2>
     *
     * {@code DispatchProviderCallbacksService} makes this call INSIDE the row's transaction, so this
     * timeout is also the longest a hung receiver can hold one row lock and one database connection.
     * The correct shape at scale is claim-then-send-then-ack with a lease; for a simulator posting to
     * localhost that is machinery for a problem it will not have (ADR-017, accepted costs).
     */
    @Bean
    CallbackSender callbackSender(
        SimulatorProperties simulatorProperties,
        SimulatorDispatchProperties dispatchProperties,
        @Value("${paymesh.provider.callback-secret}") String callbackSecret,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) dispatchProperties.readTimeout().toMillis());
        requestFactory.setReadTimeout((int) dispatchProperties.readTimeout().toMillis());

        return new HttpCallbackSender(
            RestClient.builder().requestFactory(requestFactory).build(),
            simulatorProperties.callbackUrl(),
            simulatorProperties.payoutCallbackUrl(),
            callbackSecret,
            objectMapper,
            clock
        );
    }

    @Bean
    com.paymesh.simulator.application.SimulatedPayoutRepository simulatedPayoutRepository(
        com.paymesh.simulator.infrastructure.persistence.jpa.SpringDataSimulatedPayoutRepository payouts
    ) {
        return new com.paymesh.simulator.infrastructure.persistence.jpa.JpaSimulatedPayoutRepository(payouts);
    }

    @Bean
    com.paymesh.simulator.application.CreateSimulatedPayoutService createSimulatedPayoutService(
        com.paymesh.simulator.application.SimulatedPayoutRepository simulatedPayoutRepository,
        OutboundCallbackRepository outboundCallbackRepository,
        CallbackBodyWriter callbackBodyWriter,
        org.springframework.transaction.support.TransactionTemplate transactionTemplate,
        java.time.Clock clock
    ) {
        return new com.paymesh.simulator.application.CreateSimulatedPayoutService(
            simulatedPayoutRepository, outboundCallbackRepository, callbackBodyWriter,
            transactionTemplate, clock
        );
    }

    @Bean
    CreateSimulatedPaymentService createSimulatedPaymentService(
        SimulatedPaymentRepository simulatedPaymentRepository,
        OutboundCallbackRepository outboundCallbackRepository,
        FailureProfileRepository failureProfileRepository,
        CallbackBodyWriter callbackBodyWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CreateSimulatedPaymentService(
            simulatedPaymentRepository,
            outboundCallbackRepository,
            failureProfileRepository,
            callbackBodyWriter,
            transactionTemplate,
            clock
        );
    }

    @Bean
    CaptureSimulatedPaymentService captureSimulatedPaymentService(
        SimulatedPaymentRepository simulatedPaymentRepository,
        OutboundCallbackRepository outboundCallbackRepository,
        FailureProfileRepository failureProfileRepository,
        CallbackBodyWriter callbackBodyWriter,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CaptureSimulatedPaymentService(
            simulatedPaymentRepository,
            outboundCallbackRepository,
            failureProfileRepository,
            callbackBodyWriter,
            transactionTemplate,
            clock
        );
    }

    @Bean
    CreateSimulatedRefundService createSimulatedRefundService(
        SimulatedPaymentRepository simulatedPaymentRepository,
        SimulatedRefundRepository simulatedRefundRepository,
        FailureProfileRepository failureProfileRepository,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new CreateSimulatedRefundService(
            simulatedPaymentRepository,
            simulatedRefundRepository,
            failureProfileRepository,
            transactionTemplate,
            clock
        );
    }

    @Bean
    ExportReconciliationService exportReconciliationService(
        SimulatedPaymentRepository simulatedPaymentRepository,
        SimulatedRefundRepository simulatedRefundRepository
    ) {
        return new ExportReconciliationService(simulatedPaymentRepository, simulatedRefundRepository);
    }

    @Bean
    ConfigureFailureProfileService configureFailureProfileService(
        FailureProfileRepository failureProfileRepository,
        Clock clock
    ) {
        return new ConfigureFailureProfileService(failureProfileRepository, clock);
    }

    /**
     * A plain bean that exists whether the timer does or not, so every test drives {@code dispatch()}
     * directly and none of them needs a scheduler.
     */
    @Bean
    DispatchProviderCallbacksService dispatchProviderCallbacksService(
        OutboundCallbackRepository outboundCallbackRepository,
        CallbackSender callbackSender,
        SimulatorDispatchProperties dispatchProperties,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new DispatchProviderCallbacksService(
            outboundCallbackRepository,
            callbackSender,
            transactionTemplate,
            clock,
            dispatchProperties.batchSize(),
            dispatchProperties.maxAttempts(),
            dispatchProperties.retryDelay()
        );
    }

    /**
     * THE TIMER, AND IT IS ABSENT UNDER {@code dev}.
     * <p>
     * {@code @ConditionalOnProperty} rather than a flag the bean reads, so switching it off removes
     * the timer instead of running a no-op every few seconds. {@code matchIfMissing = true} keeps the
     * real default in {@code application.yaml}; {@code application-dev.yaml} -- the profile every
     * {@code @SpringBootTest} runs under -- is the one place it is turned off, because a dispatcher
     * POSTing callbacks at PayMesh while a test asserts on a payment intent is a flake generator.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "paymesh.simulator.dispatch", name = "enabled", matchIfMissing = true
    )
    SimulatorCallbackDispatcher simulatorCallbackDispatcher(
        DispatchProviderCallbacksService dispatchProviderCallbacksService
    ) {
        return new SimulatorCallbackDispatcher(dispatchProviderCallbacksService);
    }

    /**
     * THE AUTHENTICATION FOR {@code /sim/v1/**}. There is no other.
     * <p>
     * Ordered one after the Spring Security chain, exactly like
     * {@code ProviderCallbackSignatureFilter}: the chain runs first and says {@code permitAll()} for
     * this prefix -- there is no bearer token to evaluate -- and this filter is what actually decides.
     * Registering it before the chain would put an unauthenticated filter in front of every route in
     * the application, not just this one.
     */
    @Bean
    FilterRegistrationBean<SimulatorApiKeyFilter> simulatorApiKeyFilterRegistration(
        SimulatorProperties simulatorProperties,
        ObjectMapper objectMapper
    ) {
        FilterRegistrationBean<SimulatorApiKeyFilter> registration =
            new FilterRegistrationBean<>(new SimulatorApiKeyFilter(
                simulatorProperties.apiKey(), objectMapper
            ));

        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 2);
        return registration;
    }
}
