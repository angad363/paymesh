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
import com.paymesh.merchant.infrastructure.persistence.jpa.JpaApiCredentialRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.SpringDataApiCredentialRepository;
import com.paymesh.merchant.infrastructure.security.ApiCredentialAuthenticator;
import com.paymesh.shared.security.ApiKeyAuthenticator;
import com.paymesh.merchant.infrastructure.persistence.jpa.JpaKycSubmissionRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.JpaMerchantStatusHistoryRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.SpringDataKycSubmissionRepository;
import com.paymesh.merchant.infrastructure.persistence.jpa.SpringDataMerchantStatusHistoryRepository;
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
    RegisterMerchantService registerMerchantService(
        MerchantRepository merchantRepository,
        com.paymesh.shared.outbox.application.OutboxWriter outbox,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new RegisterMerchantService(merchantRepository, outbox, transactionTemplate, clock);
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
        com.paymesh.shared.outbox.application.OutboxWriter outbox,
        TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new ChangeMerchantStatusService(
            merchantRepository, merchantStatusHistoryRepository, getMerchantService,
            outbox, transactionTemplate, clock
        );
    }

    // The MerchantStatusGate bean moved to SharedConfiguration in ADR-039: it is now answered from
    // the event-fed merchant_ref projection (MerchantRefStore), not the merchants table, so the
    // merchant module no longer implements it -- it only EMITS the lifecycle events that feed it.

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
