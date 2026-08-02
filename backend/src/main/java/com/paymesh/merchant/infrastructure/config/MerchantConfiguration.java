package com.paymesh.merchant.infrastructure.config;

import com.paymesh.merchant.application.ApiCredentialRepository;
import com.paymesh.merchant.application.ChangeMerchantStatusService;
import com.paymesh.merchant.application.GetMerchantService;
import com.paymesh.merchant.application.IssueApiCredentialService;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.application.MerchantStatusHistoryRepository;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.merchant.application.UpdateMerchantService;
import com.paymesh.merchant.infrastructure.MerchantStatusGateAdapter;
import com.paymesh.merchant.infrastructure.persistence.jpa.JpaApiCredentialRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.JpaMerchantStatusHistoryRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.SpringDataApiCredentialRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.SpringDataMerchantStatusHistoryRepository;
import com.paymesh.merchant.infrastructure.security.ApiKeyAuthenticationFilter;
import com.paymesh.shared.tenant.MerchantStatusGate;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.paymesh.merchant.infrastructure.persistence.jpa.JpaMerchantRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.SpringDataMerchantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class MerchantConfiguration {


    @Bean
    MerchantRepository merchantRepository(SpringDataMerchantRepository springDataMerchantRepository) {
        return new JpaMerchantRepository(springDataMerchantRepository);
    }

    @Bean
    RegisterMerchantService registerMerchantService(MerchantRepository merchantRepository, Clock clock) {
        return new RegisterMerchantService(merchantRepository, clock);
    }

    @Bean
    GetMerchantService getMerchantService(MerchantRepository merchantRepository) {
        return new GetMerchantService(merchantRepository);
    }

    @Bean
    MerchantStatusHistoryRepository merchantStatusHistoryRepository(
        SpringDataMerchantStatusHistoryRepository history
    ) {
        return new JpaMerchantStatusHistoryRepository(history);
    }

    @Bean
    ApiCredentialRepository apiCredentialRepository(
        SpringDataApiCredentialRepository credentials
    ) {
        return new JpaApiCredentialRepository(credentials);
    }

    @Bean
    UpdateMerchantService updateMerchantService(
        MerchantRepository merchantRepository,
        GetMerchantService getMerchantService,
        Clock clock
    ) {
        return new UpdateMerchantService(merchantRepository, getMerchantService, clock);
    }

    @Bean
    ChangeMerchantStatusService changeMerchantStatusService(
        MerchantRepository merchantRepository,
        MerchantStatusHistoryRepository merchantStatusHistoryRepository,
        GetMerchantService getMerchantService,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new ChangeMerchantStatusService(
            merchantRepository, merchantStatusHistoryRepository, getMerchantService,
            transactionTemplate, clock
        );
    }

    @Bean
    IssueApiCredentialService issueApiCredentialService(
        ApiCredentialRepository apiCredentialRepository,
        Clock clock
    ) {
        return new IssueApiCredentialService(apiCredentialRepository, clock);
    }

    /**
     * THE MERCHANT MODULE ANSWERING THE PLATFORM'S QUESTION.
     * <p>
     * {@code shared} declares {@link MerchantStatusGate} and this implements it, so the arrow keeps
     * pointing the way it already points -- a capability may see {@code shared}, and {@code shared}
     * still names no capability. Same shape as Payment implementing Order's
     * {@code PaymentActivityLookup} (ADR-008).
     */
    @Bean
    MerchantStatusGate merchantStatusGate(MerchantRepository merchantRepository) {
        return new MerchantStatusGateAdapter(merchantRepository);
    }

    /**
     * SERVER-TO-SERVER AUTHENTICATION. Ordered immediately after the security chain so it runs on a
     * request the chain has already let through, and constructed inline so Boot cannot also
     * auto-register it and run it twice.
     */
    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthenticationFilterRegistration(
        ApiCredentialRepository apiCredentialRepository,
        ObjectMapper objectMapper
    ) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
            new FilterRegistrationBean<>(
                new ApiKeyAuthenticationFilter(apiCredentialRepository, objectMapper)
            );

        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
