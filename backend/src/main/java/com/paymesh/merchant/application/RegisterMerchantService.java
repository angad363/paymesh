package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

public final class RegisterMerchantService {

    private final MerchantRepository merchantRepository;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RegisterMerchantService(
        MerchantRepository merchantRepository,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.merchantRepository = merchantRepository;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Merchant register(RegisterMerchantCommand command) {
        if(command == null) {
            throw new IllegalArgumentException("Register Merchant Command cannot be null");
        }

        Instant now = Instant.now(clock);

        Merchant merchant = Merchant.register(
            MerchantId.generate(),
            command.businessName(),
            command.email(),
            command.country(),
            command.defaultCurrency(),
            now
        );

        // The readable pre-check; the DB unique index is the real guard. Left outside the
        // transaction as before -- it is a fast fail, not the source of truth.
        if(merchantRepository.existsByEmail(merchant.email())) {
            throw new MerchantEmailAlreadyExistsException(merchant.email());
        }

        // The insert and the merchant.registered event commit together (ADR-010/039). This service
        // took no transaction before ADR-039, because it wrote no event; it does now, so consumers'
        // merchant_ref projections learn the merchant exists.
        return transactions.execute(status -> {
            Merchant saved = merchantRepository.save(merchant);
            outbox.append(MerchantLifecycleEvents.of(saved, now));
            return saved;
        });
    }
}
