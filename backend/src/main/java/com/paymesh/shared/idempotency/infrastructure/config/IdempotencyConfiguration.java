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
     * THE REGISTRY. A public write becomes idempotent by being listed here and nowhere else; a
     * route that is absent passes through the filter untouched.
     * <p>
     * Declarations are {@code "METHOD /path/template"} -- the template, not a concrete URI, because
     * the template is what gets stored as the record's endpoint.
     * <p>
     * Still absent, deliberately: {@code POST /api/v1/merchants} is unauthenticated and so has no
     * merchant to scope a key by, and {@code POST /api/v1/customers} creates no financial effect
     * worth the header.
     */
    private static final List<String> IDEMPOTENT_ROUTES = List.of(
        "POST /api/v1/orders",
        "POST /api/v1/orders/{orderId}/cancel",
        "POST /api/v1/payment-intents",
        "POST /api/v1/payment-intents/{paymentIntentId}/payment-method",
        "POST /api/v1/payment-intents/{paymentIntentId}/confirm",
        "POST /api/v1/payment-intents/{paymentIntentId}/cancel",
        // Capture is the route on this list that most needs to be here: it is the one that takes
        // funds. A retried capture whose first attempt already committed must replay the stored
        // response, not attempt a second collection -- and while the state machine would refuse the
        // second one anyway (SUCCEEDED is not capturable), a 409 is the wrong answer to a network
        // retry of a request that worked.
        "POST /api/v1/payment-intents/{paymentIntentId}/capture",
        // REFUNDS BELONG HERE FOR THE SAME REASON CAPTURE DOES, in the opposite direction: a
        // retried create whose first attempt already committed must replay the stored response
        // rather than send a second lot of money back. Unlike capture there is no state machine to
        // catch the second one -- two refunds of one payment are perfectly legal, which is exactly
        // what makes a network retry dangerous here. The over-refund trigger would stop a full
        // duplicate, and would NOT stop two halves of one.
        "POST /api/v1/refunds",
        "POST /api/v1/refunds/{refundId}/cancel"
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
