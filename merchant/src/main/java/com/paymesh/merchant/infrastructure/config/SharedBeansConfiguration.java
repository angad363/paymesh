package com.paymesh.merchant.infrastructure.config;

import com.paymesh.shared.security.ApiKeyAuthenticationFilter;
import com.paymesh.shared.security.ApiKeyAuthenticator;
import com.paymesh.shared.tenant.MerchantStatusFilter;
import com.paymesh.shared.tenant.MerchantStatusGate;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * The platform beans the monolith keeps in {@code shared.infrastructure.SharedConfiguration}, trimmed
 * to what this deployable needs (ADR-046): {@link Clock}, {@link TransactionTemplate} (ADR-010), the
 * ApiKey filter (inside the security chain, not the servlet chain -- see the monolith's copy for why),
 * and the merchant status filter. NOT here: the {@code merchant_ref} projection -- this service owns
 * the status, so its gate reads its own {@code merchants} table.
 */
@Configuration
public class SharedBeansConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
        ApiKeyAuthenticator apiKeyAuthenticator,
        ObjectMapper objectMapper
    ) {
        return new ApiKeyAuthenticationFilter(apiKeyAuthenticator, objectMapper);
    }

    /** Stops Boot also registering the filter above in the servlet chain, where it would run twice. */
    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthenticationFilterNotInServletChain(
        ApiKeyAuthenticationFilter filter
    ) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
            new FilterRegistrationBean<>(filter);

        registration.setEnabled(false);
        return registration;
    }

    /**
     * ADR-021 enforcement unchanged: a non-active merchant cannot write. Lifecycle-transition routes
     * stay exempt via the filter's platform-admin rule, or suspension would be irreversible.
     */
    @Bean
    FilterRegistrationBean<MerchantStatusFilter> merchantStatusFilterRegistration(
        MerchantStatusGate merchantStatusGate,
        ObjectMapper objectMapper
    ) {
        FilterRegistrationBean<MerchantStatusFilter> registration =
            new FilterRegistrationBean<>(new MerchantStatusFilter(merchantStatusGate, objectMapper));

        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 2);
        return registration;
    }
}
