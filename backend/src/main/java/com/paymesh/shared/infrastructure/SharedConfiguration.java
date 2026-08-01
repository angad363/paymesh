package com.paymesh.shared.infrastructure;

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
}
