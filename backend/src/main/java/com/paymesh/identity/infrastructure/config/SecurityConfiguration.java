package com.paymesh.identity.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Keeps every endpoint open, exactly as it was before spring-boot-starter-security
 * joined the build.
 *
 * <p>The starter is here for BCryptPasswordEncoder, but adding it switches on
 * Spring Security's auto-configuration, which secures every request with HTTP Basic
 * and a random generated password. That would have broken the merchant API and its
 * tests as a side effect of adding a password hasher -- so this chain turns it back
 * off.
 *
 * <p>This class is the whole of PayMesh's authorization today: nothing enforces the
 * access tokens the identity module issues. That is deliberate and scoped to the
 * next change, which replaces {@code permitAll} with a JWT resource-server filter
 * and leaves only the four /api/v1/auth endpoints public.
 *
 * <p>CSRF is disabled because PayMesh is a stateless JSON API authenticated by
 * tokens rather than cookies; there is no ambient credential for a cross-site form
 * post to ride on. Sessions are stateless for the same reason.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(formLogin -> formLogin.disable())
            .logout(logout -> logout.disable())
            .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
            .build();
    }
}
