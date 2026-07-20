package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GetMerchantServiceTest {

    @Test
    void returnsMerchantWhenItExists() {
        Merchant merchant = createMerchant();

        MerchantRepository repository =
            new StubMerchantRepository(
                Optional.of(merchant)
            );

        GetMerchantService service =
            new GetMerchantService(repository);

        Merchant result =
            service.getById(merchant.merchantId());

        assertSame(merchant, result);
    }

    @Test
    void throwsWhenMerchantDoesNotExist() {
        MerchantRepository repository =
            new StubMerchantRepository(
                Optional.empty()
            );

        GetMerchantService service =
            new GetMerchantService(repository);

        assertThrows(
            MerchantNotFoundException.class,
            () -> service.getById(
                MerchantId.generate()
            )
        );
    }

    @Test
    void rejectsNullMerchantId() {
        MerchantRepository repository =
            new StubMerchantRepository(
                Optional.empty()
            );

        GetMerchantService service =
            new GetMerchantService(repository);

        assertThrows(
            IllegalArgumentException.class,
            () -> service.getById(null)
        );
    }

    private static Merchant createMerchant() {
        return Merchant.register(
            MerchantId.generate(),
            "FreshBrew Cafe",
            "owner@freshbrew.example",
            "IN",
            "INR",
            Instant.parse("2026-07-19T10:15:30Z")
        );
    }

    private static final class StubMerchantRepository
        implements MerchantRepository {

        private final Optional<Merchant> merchant;

        private StubMerchantRepository(
            Optional<Merchant> merchant
        ) {
            this.merchant = merchant;
        }

        @Override
        public boolean existsByEmail(
            String normalizedEmail
        ) {
            return false;
        }

        @Override
        public Merchant save(Merchant merchant) {
            return merchant;
        }

        @Override
        public Optional<Merchant> findByMerchantId(
            MerchantId merchantId
        ) {
            return merchant;
        }
    }
}
