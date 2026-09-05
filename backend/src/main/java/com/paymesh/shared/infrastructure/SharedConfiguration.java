package com.paymesh.shared.infrastructure;

import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.security.ApiKeyAuthenticationFilter;
import com.paymesh.shared.security.ApiKeyAuthenticator;
import com.paymesh.shared.tenant.MerchantRefProjector;
import com.paymesh.shared.tenant.MerchantRefStore;
import com.paymesh.shared.tenant.MerchantStatusFilter;
import com.paymesh.shared.tenant.MerchantStatusGate;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Beans that every capability needs and none of them owns.
 * <p>
 * Time is injected rather than read from Instant.now() so services stay deterministic under test.
 * It lives here, not in a feature configuration, because the second capability to need it would
 * otherwise either duplicate the bean definition (a startup failure) or reach across a module
 * boundary to borrow the first one's.
 */
@Configuration
public class SharedConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * THE ONLY WAY TO OPEN A MULTI-STATEMENT TRANSACTION IN THIS CODEBASE (ADR-010).
     * <p>
     * {@code @Transactional} is not an option on an application service here: those are {@code
     * final} classes with no interface, Boot defaults to CGLIB proxies, and a final class cannot be
     * subclassed -- the context refuses to refresh with "Cannot subclass final class". Dropping
     * final to get the annotation was rejected for a second reason: this project wires everything by
     * hand precisely so a dependency is visible in the constructor, and a transaction boundary is
     * the last thing that should be invisible there.
     * <p>
     * Injected as a bean rather than constructed per service so every transaction in the system
     * shares one propagation and isolation policy, and so a test can see which services took one.
     */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * THE MERCHANT REFERENCE PROJECTION (ADR-039), both sides of it.
     * <p>
     * It answers the platform's {@link MerchantStatusGate} from the event-fed {@code merchant_ref}
     * copies instead of the {@code merchants} table -- the last thing that made a consumer read the
     * merchant's authoritative table -- and it is what {@link MerchantRefProjector} writes. Reached
     * by schema-qualified SQL through a {@link JdbcTemplate} rather than JPA, because six tables share
     * the name {@code merchant_ref} and a bare-named entity could not resolve them (see the class).
     * <p>
     * It lives in {@code shared}, not {@code merchant}: it is platform infrastructure that spans
     * every service's schema, and the merchant module is now only the EMITTER of the events that feed
     * it. At extraction each service keeps its own copy, fed from Kafka.
     */
    // One bean, both roles: MerchantRefStore implements MerchantStatusGate, so the filter's
    // MerchantStatusGate dependency resolves to this by type and the projectors inject it directly.
    // A second bean returning the same instance as the interface would make MerchantRefStore
    // ambiguous, so there is deliberately only this one.
    @Bean
    MerchantRefStore merchantRefStore(DataSource dataSource) {
        return new MerchantRefStore(new JdbcTemplate(dataSource));
    }

    /**
     * One projector per merchant lifecycle event, registered here as {@link EventHandler}s the
     * dispatcher collects -- the same registration shape every capability uses for its consumers.
     * Four beans, one class, differing only in the event type (the {@code NotificationEventHandler}
     * pattern).
     */
    @Bean
    EventHandler merchantRegisteredProjector(MerchantRefStore store) {
        return new MerchantRefProjector("merchant.registered", store);
    }

    @Bean
    EventHandler merchantActivatedProjector(MerchantRefStore store) {
        return new MerchantRefProjector("merchant.activated", store);
    }

    @Bean
    EventHandler merchantSuspendedProjector(MerchantRefStore store) {
        return new MerchantRefProjector("merchant.suspended", store);
    }

    @Bean
    EventHandler merchantClosedProjector(MerchantRefStore store) {
        return new MerchantRefProjector("merchant.closed", store);
    }

    /**
     * THE ENFORCEMENT THAT MAKES MERCHANT STATUS MEAN SOMETHING (ADR-021).
     * <p>
     * Registered here rather than in the Merchant module, because it guards every capability's
     * writes and belongs to the platform rather than to the module that happens to own the column.
     * The Merchant module supplies the answer through {@code MerchantStatusGate}; this decides what
     * to do with it.
     * <p>
     * Ordered after the security chain so a caller has already been authenticated, and constructed
     * inline so Boot cannot also auto-register it and run it twice.
     */
    @Bean
    FilterRegistrationBean<MerchantStatusFilter> merchantStatusFilterRegistration(
        MerchantStatusGate merchantStatusGate,
        ObjectMapper objectMapper
    ) {
        FilterRegistrationBean<MerchantStatusFilter> registration =
            new FilterRegistrationBean<>(
                new MerchantStatusFilter(merchantStatusGate, objectMapper)
            );

        // AFTER the API-key filter, which may be what establishes the caller in the first place.
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 2);
        return registration;
    }

    /**
     * NOT a {@code FilterRegistrationBean}, unlike every other filter here.
     * <p>
     * A registration bean puts a filter in the servlet chain, which runs <b>after</b> Spring
     * Security's -- and the security chain ends with {@code .anyRequest().authenticated()}, so an
     * ApiKey request would be refused 401 before the filter ever saw the header. This is a plain
     * bean that {@code SecurityConfiguration} inserts INTO the security chain with
     * {@code addFilterBefore}.
     * <p>
     * Being a plain {@code Filter} bean would normally make Boot auto-register it in the servlet
     * chain as well, running it twice. {@code OncePerRequestFilter} makes the second run a no-op,
     * and the {@code FilterRegistrationBean} below disables the auto-registration outright so it
     * does not happen at all.
     */
    @Bean
    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
        ApiKeyAuthenticator apiKeyAuthenticator,
        ObjectMapper objectMapper
    ) {
        return new ApiKeyAuthenticationFilter(apiKeyAuthenticator, objectMapper);
    }

    /**
     * Stops Boot auto-registering the filter above in the servlet chain, where it would run a
     * second time -- outside the security chain and after it, which is the position that does not
     * work.
     */
    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthenticationFilterNotInServletChain(
        ApiKeyAuthenticationFilter filter
    ) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
            new FilterRegistrationBean<>(filter);

        registration.setEnabled(false);
        return registration;
    }
}
