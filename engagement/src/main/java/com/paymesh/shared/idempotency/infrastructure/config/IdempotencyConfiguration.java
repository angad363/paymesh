package com.paymesh.shared.idempotency.infrastructure.config;

import com.paymesh.shared.idempotency.application.IdempotencyRepository;
import com.paymesh.shared.idempotency.infrastructure.IdempotencyFilter;
import com.paymesh.shared.idempotency.infrastructure.IdempotentRoutes;
import com.paymesh.shared.idempotency.infrastructure.persistence.jpa.JpaIdempotencyRepository;
import com.paymesh.shared.idempotency.infrastructure.persistence.jpa.SpringDataIdempotencyRepository;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;

/**
 * Explicit wiring for the idempotency layer (no component scanning of application classes).
 */
@Configuration
public class IdempotencyConfiguration {

    /**
     * THE REGISTRY, this deployable's own slice of the monolith's list (ADR-043, following the
     * ADR-042 precedent): only the routes Engagement itself serves. A public write becomes
     * idempotent by being listed here and nowhere else; a route that is absent passes through the
     * filter untouched.
     * <p>
     * Declarations are {@code "METHOD /path/template"} -- the template, not a concrete URI, because
     * the template is what gets stored as the record's endpoint.
     * <p>
     * {@code POST /internal/v1/audit-exports} is deliberately NOT here, matching the monolith's own
     * asymmetry before this extraction: only report exports carry the header today.
     */
    private static final List<String> IDEMPOTENT_ROUTES = List.of(
        // Requesting a CSV export. Not money, and the state machine would not refuse a second one
        // -- which is exactly why it is here: a retried request whose first attempt already
        // committed would produce a SECOND export row, and the merchant would poll one id while a
        // duplicate rendered the same window beside it. The same shape as refunds, in a much
        // cheaper currency.
        "POST /api/v1/report-exports"
    );

    @Bean
    IdempotencyRepository idempotencyRepository(SpringDataIdempotencyRepository springDataRepository) {
        return new JpaIdempotencyRepository(springDataRepository);
    }

    @Bean
    IdempotentRoutes idempotentRoutes() {
        return new IdempotentRoutes(IDEMPOTENT_ROUTES);
    }

    /**
     * Registered after Spring Security's filter chain, which sits at
     * {@link SecurityFilterProperties#DEFAULT_FILTER_ORDER} (-100). The filter needs the authenticated
     * merchant, so running before that chain would give it an empty security context and no scope
     * to key on.
     * <p>
     * The filter is constructed here rather than declared as its own {@code @Bean} so Boot cannot
     * also auto-register it and run it twice.
     */
    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
        IdempotentRoutes idempotentRoutes,
        IdempotencyRepository idempotencyRepository,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(
            new IdempotencyFilter(idempotentRoutes, idempotencyRepository, objectMapper, clock)
        );

        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
