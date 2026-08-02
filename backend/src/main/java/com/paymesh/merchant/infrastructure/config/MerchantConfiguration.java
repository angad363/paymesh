package com.paymesh.merchant.infrastructure.config;

import com.paymesh.merchant.application.ApiCredentialRepository;
import com.paymesh.merchant.application.ChangeMerchantStatusService;
import com.paymesh.merchant.application.GetMerchantService;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.application.IssueApiCredentialService;
import com.paymesh.merchant.application.KycSubmissionRepository;
import com.paymesh.merchant.application.MerchantStatusHistoryRepository;
import com.paymesh.merchant.application.ReviewKycSubmissionService;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.merchant.application.UpdateMerchantService;
import com.paymesh.merchant.infrastructure.MerchantStatusGateAdapter;
import com.paymesh.merchant.infrastructure.persistence.jpa.JpaApiCredentialRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.SpringDataApiCredentialRepository;
import com.paymesh.merchant.infrastructure.security.ApiCredentialAuthenticator;
import com.paymesh.shared.security.ApiKeyAuthenticator;
import com.paymesh.merchant.infrastructure.persistence.jpa.JpaKycSubmissionRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.JpaMerchantStatusHistoryRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.SpringDataKycSubmissionRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.SpringDataMerchantStatusHistoryRepository;
import com.paymesh.shared.tenant.MerchantStatusGate;
import org.springframework.transaction.support.TransactionTemplate;
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

    @Bean
    KycSubmissionRepository kycSubmissionRepository(SpringDataKycSubmissionRepository submissions) {
        return new JpaKycSubmissionRepository(submissions);
    }

    @Bean
    ReviewKycSubmissionService reviewKycSubmissionService(
        KycSubmissionRepository kycSubmissionRepository,
        ChangeMerchantStatusService changeMerchantStatusService,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new ReviewKycSubmissionService(
            kycSubmissionRepository, changeMerchantStatusService, transactionTemplate, clock
        );
    }

    @Bean
    ApiCredentialRepository apiCredentialRepository(SpringDataApiCredentialRepository credentials) {
        return new JpaApiCredentialRepository(credentials);
    }

    @Bean
    IssueApiCredentialService issueApiCredentialService(
        ApiCredentialRepository apiCredentialRepository,
        Clock clock
    ) {
        return new IssueApiCredentialService(apiCredentialRepository, clock);
    }

    /**
     * The Merchant module answering the platform's authentication question, the same shape as
     * {@code MerchantStatusGate}: {@code shared} declares the port, this implements it, and
     * {@code shared} still names no capability.
     */
    @Bean
    ApiKeyAuthenticator apiKeyAuthenticator(
        ApiCredentialRepository apiCredentialRepository,
        Clock clock
    ) {
        return new ApiCredentialAuthenticator(apiCredentialRepository, clock);
    }
}
