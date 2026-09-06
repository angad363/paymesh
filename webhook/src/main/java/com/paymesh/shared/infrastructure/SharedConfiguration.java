package com.paymesh.shared.infrastructure;

import com.paymesh.shared.outbox.application.EventHandler;
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

import javax.sql.DataSource;

/**
 * Webhook's own copy of the monolith's {@code shared.infrastructure.SharedConfiguration}
 * (ADR-042 section 1), trimmed to what this deployable still needs:
 * <p>
 * NOT HERE: {@code Clock}/{@code TransactionTemplate} -- those live in this module's own
 * {@code SharedBeansConfiguration}, the same split {@code provider-sim} made (ADR-041). NOT HERE
 * EITHER: the {@code ApiKeyAuthenticationFilter} beans -- webhook is JWT-only (ADR-042 section 3),
 * so that filter and its registration-disabling twin do not exist on this side of the door.
 * <p>
 * What remains is the merchant reference projection (both sides: {@link MerchantRefStore} and the
 * four {@link MerchantRefProjector} beans that feed it from {@code merchant.*} events) and the
 * {@link MerchantStatusFilter} that enforces it on every authenticated write.
 */
@Configuration
public class SharedConfiguration {

    /**
     * ONE SCHEMA, NOT SIX. The monolith's copy fans this write out to every service schema that
     * still carries a {@code merchant_ref} table, because one in-process projector fed all of them.
     * {@code webhook_svc} cannot write another service's schema (ADR-038 fencing) -- and does not
     * need to, now that each extraction gets its own consumer group and its own copy of this class.
     * {@link MerchantRefStore}'s own javadoc already named this day: "at extraction each service
     * keeps only its own... and this list collapses to one."
     */
    @Bean
    MerchantRefStore merchantRefStore(DataSource dataSource) {
        return new MerchantRefStore(new JdbcTemplate(dataSource));
    }

    /**
     * One projector per merchant lifecycle event, registered here as {@link EventHandler}s the
     * dispatcher collects -- the same registration shape the monolith uses for its consumers. Four
     * beans, one class, differing only in the event type.
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
     * THE ENFORCEMENT THAT MAKES MERCHANT STATUS MEAN SOMETHING (ADR-021), unchanged by
     * extraction (ADR-042 section 3): register and rotate are merchant writes, and the gate still
     * reads the (now webhook-local) {@code merchant_ref} projection.
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

        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 2);
        return registration;
    }
}
