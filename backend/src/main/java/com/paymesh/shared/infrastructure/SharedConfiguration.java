package com.paymesh.shared.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
