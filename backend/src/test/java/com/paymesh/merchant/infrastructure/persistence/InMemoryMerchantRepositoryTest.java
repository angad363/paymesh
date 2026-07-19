package com.paymesh.merchant.infrastructure.persistence;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryMerchantRepositoryTest {

    @Test
    void savesMerchant() {
        InMemoryMerchantRepository repository = new InMemoryMerchantRepository();

        Merchant merchant = Merchant.register(
            MerchantId.generate(),
            "FreshBrew Cafe",
            "owner@freshbrew.example",
            "IN",
            "INR",
            Instant.parse("2026-07-19T10:15:30Z")
        );

        Merchant savedMerchant = repository.save(merchant);

        assertSame(merchant, savedMerchant);
        assertEquals(1, repository.size());
    }

    @Test
    void reportsThatSavedMerchantEmailExists() {
        InMemoryMerchantRepository repository =
            new InMemoryMerchantRepository();

        Merchant merchant = Merchant.register(
            MerchantId.generate(),
            "FreshBrew Cafe",
            "owner@freshbrew.example",
            "IN",
            "INR",
            Instant.parse("2026-07-19T10:15:30Z")
        );

        repository.save(merchant);
        assertTrue(
            repository.existsByEmail(
                "owner@freshbrew.example"
            )
        );
    }

    @Test
    void reportsThatUnknownEmailDoesNotExist() {
        InMemoryMerchantRepository repository =
            new InMemoryMerchantRepository();

        assertFalse(
            repository.existsByEmail(
                "unknown@example.com"
            )
        );
    }

    @Test
    void rejectsNullMerchant() {
        InMemoryMerchantRepository repository =
            new InMemoryMerchantRepository();

        assertThrows(
            IllegalArgumentException.class,
            () -> repository.save(null)
        );

        assertEquals(0, repository.size());
    }
}
