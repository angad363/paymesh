package com.paymesh.shared.infrastructure;

import com.paymesh.shared.tenant.MerchantStatusFilter;
import com.paymesh.shared.tenant.MerchantStatusGate;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
}
