package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantId;

import java.time.Clock;
import java.time.Instant;

public final class RegisterMerchantService {

    private final MerchantRepository merchantRepository;
    private final Clock clock;

    public RegisterMerchantService(MerchantRepository merchantRepository, Clock clock) {
        this.merchantRepository = merchantRepository;
        this.clock = clock;
    }

    public Merchant register(RegisterMerchantCommand command) {
        if(command == null) {
            throw new IllegalArgumentException("Register Merchant Command cannot be null");
        }

        Merchant merchant = Merchant.register(
            MerchantId.generate(),
            command.businessName(),
            command.email(),
            command.country(),
            command.defaultCurrency(),
            Instant.now(clock)
        );

        if(merchantRepository.existsByEmail(merchant.email())) {
            throw new MerchantEmailAlreadyExistsException(merchant.email());
        }

        return merchantRepository.save(merchant);


    }
}
