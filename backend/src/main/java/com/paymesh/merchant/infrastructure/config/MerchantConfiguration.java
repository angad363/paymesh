package com.paymesh.merchant.infrastructure.config;

import com.paymesh.merchant.application.GetMerchantService;
import com.paymesh.merchant.application.MerchantRepository;import com.paymesh.merchant.application.RegisterMerchantService;import com.paymesh.merchant.infrastructure.persistence.InMemoryMerchantRepository;import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class MerchantConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MerchantRepository merchantRepository() {
        return new InMemoryMerchantRepository();
    }

    @Bean
    RegisterMerchantService registerMerchantService(MerchantRepository merchantRepository, Clock clock) {
        return new RegisterMerchantService(merchantRepository, clock);
    }

    @Bean
    GetMerchantService getMerchantService(MerchantRepository merchantRepository) {
        return new GetMerchantService(merchantRepository);
    }
}
