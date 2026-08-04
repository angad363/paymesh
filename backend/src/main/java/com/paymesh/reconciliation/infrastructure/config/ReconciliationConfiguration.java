package com.paymesh.reconciliation.infrastructure.config;

import com.paymesh.reconciliation.application.PaymentRepair;
import com.paymesh.reconciliation.application.ProviderReconciliationSource;
import com.paymesh.reconciliation.application.ReconcileProviderDayService;
import com.paymesh.reconciliation.application.RefundRepair;
import com.paymesh.reconciliation.infrastructure.http.HttpProviderReconciliationSource;
import com.paymesh.reconciliation.infrastructure.payment.PaymentModuleRepair;
import com.paymesh.reconciliation.infrastructure.refund.RefundModuleRepair;
import com.paymesh.reconciliation.infrastructure.schedule.ReconciliationSweeper;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.refund.application.RecordRefundCallbackService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/**
 * Explicit wiring for reconciliation (ADR-026). No component scanning of application classes.
 * <p>
 * This is the only file besides the two adapters that names Payment or Refund, and it names them
 * only to hand their services to the adapters -- constructing an adapter cannot avoid naming what it
 * adapts. {@code ModuleBoundaryTest} allowlists exactly these three paths and nothing else in the
 * module, so an accidental import in {@code application} turns the build red.
 */
@Configuration
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationConfiguration {

    /**
     * A dedicated {@link RestClient} rather than a shared one, because its timeouts belong to this
     * caller. A reconciliation fetch may legitimately take seconds -- it returns a whole day -- and
     * borrowing a client tuned for something interactive would either strangle this or loosen that.
     * <p>
     * The READ timeout is the one set here, and it is the one that matters: a provider that accepts
     * the connection and then never answers is the failure that would otherwise hold the scheduler
     * thread until the OS gave up, which on some defaults is minutes. The JDK client's connect
     * timeout lives on the {@code HttpClient} rather than the factory, so it is left at the default;
     * a connection that cannot be established fails fast on its own, and the day it does not, this
     * is the line to add it to.
     */
    @Bean
    RestClient reconciliationRestClient(ReconciliationProperties properties) {
        Duration timeout = Duration.ofMillis(properties.timeoutMs());

        // The JDK's own HTTP client, which is already on the classpath and needs no dependency. The
        // ladder stops here: there is nothing this caller does -- one GET, JSON in, no streaming,
        // no connection pooling worth tuning -- that would repay adding Apache HttpClient.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(timeout);

        // RestClient.builder() rather than an injected RestClient.Builder: Boot 4 does not
        // auto-configure that bean without the restclient starter, and SimulatorConfiguration
        // already builds its own client the same way.
        return RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .build();
    }

    @Bean
    ProviderReconciliationSource providerReconciliationSource(
        RestClient reconciliationRestClient, ReconciliationProperties properties
    ) {
        return new HttpProviderReconciliationSource(
            reconciliationRestClient, properties.apiKeyHeader(), properties.apiKey()
        );
    }

    @Bean
    PaymentRepair paymentRepair(
        RecordProviderCallbackService recordProviderCallbackService,
        ReconciliationProperties properties
    ) {
        return new PaymentModuleRepair(recordProviderCallbackService, properties.provider());
    }

    @Bean
    RefundRepair refundRepair(
        RecordRefundCallbackService recordRefundCallbackService,
        ReconciliationProperties properties
    ) {
        return new RefundModuleRepair(recordRefundCallbackService, properties.provider());
    }

    /**
     * The job. Declared unconditionally, even when the timer below is off: it is an ordinary object
     * that starts nothing on its own, and an operator reconciling a specific day by hand -- or a
     * test -- should not have to enable a scheduler to do it. Every test in this branch calls
     * {@code reconcile(date)} directly for exactly that reason.
     */
    @Bean
    ReconcileProviderDayService reconcileProviderDayService(
        ProviderReconciliationSource providerReconciliationSource,
        PaymentRepair paymentRepair,
        RefundRepair refundRepair,
        ReconciliationProperties properties,
        Clock clock
    ) {
        return new ReconcileProviderDayService(
            providerReconciliationSource, paymentRepair, refundRepair,
            properties.lookbackDays(), clock
        );
    }

    /**
     * The timer, and the only conditional bean here.
     * <p>
     * With the bean absent there is no {@code @Scheduled} method to register at all, so switching
     * {@code paymesh.reconciliation.enabled} off genuinely stops the job rather than running a no-op
     * every tick. <b>The dev profile turns it off</b> -- that is the profile the test suite runs
     * under, and a job that moves a payment to SUCCEEDED underneath an assertion is a flake
     * generator. It follows that {@code ./mvnw spring-boot:run} has it off too; exercising it by
     * hand needs {@code PAYMESH_RECONCILIATION_ENABLED=true}.
     * <p>
     * {@code @EnableScheduling} is not repeated: {@code OrderConfiguration} declares it once for the
     * whole application, and declaring it twice registers two schedulers.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "paymesh.reconciliation", name = "enabled", matchIfMissing = true
    )
    ReconciliationSweeper reconciliationSweeper(
        ReconcileProviderDayService reconcileProviderDayService
    ) {
        return new ReconciliationSweeper(reconcileProviderDayService);
    }
}
