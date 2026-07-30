package com.paymesh.merchant.infrastructure.persistence.jpa;

import com.paymesh.merchant.application.MerchantEmailAlreadyExistsException;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import com.paymesh.TestcontainersConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class JpaMerchantRepositoryTest {

    @Autowired
    private MerchantRepository repository;

    @Test
    void savesAndReadsBackAMerchant() {
        Merchant merchant = register("roundtrip@paymesh.test");

        repository.save(merchant);

        Merchant found = repository.findByMerchantId(merchant.merchantId()).orElseThrow();
        assertThat(found).usingRecursiveComparison().isEqualTo(merchant);
        assertThat(repository.existsByEmail("roundtrip@paymesh.test")).isTrue();
    }

    @Test
    void returnsEmptyWhenMerchantIdIsUnknown() {
        assertThat(repository.findByMerchantId(MerchantId.generate())).isEmpty();
    }

    /**
     * Simulates the registration race: the service's existsByEmail check passes, then someone else
     * inserts the same email before the write lands. The unique constraint must surface as the same
     * business exception, not as a raw DataIntegrityViolationException (which would be a 500).
     */
    @Test
    void translatesTheUniqueEmailConstraintIntoABusinessException() {
        repository.save(register("race@paymesh.test"));

        assertThatThrownBy(() -> repository.save(register("race@paymesh.test")))
            .isInstanceOf(MerchantEmailAlreadyExistsException.class)
            .hasMessage("A merchant already exists with email race@paymesh.test");
    }

    private static Merchant register(String email) {
        return Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            email,
            "IN",
            "INR",
            Instant.now()
        );
    }
}
