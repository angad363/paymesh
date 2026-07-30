package com.paymesh.shared.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Lets controllers declare an {@link AuthenticatedCaller} parameter and receive one built from the
 * verified token.
 */
@Configuration
public class WebSecurityMvcConfiguration implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthenticatedCallerArgumentResolver());
    }
}
