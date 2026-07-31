package com.paymesh.shared.idempotency.infrastructure.persistence.jpa;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.shared.idempotency.application.IdempotencyRepository;
import com.paymesh.shared.idempotency.domain.IdempotencyRecord;
import com.paymesh.shared.idempotency.domain.IdempotencyStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately NOT {@code @Transactional}: the point of this adapter is that each call commits on
 * its own, and a test transaction wrapping them all would hide exactly the behaviour under test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class JpaIdempotencyRepositoryTest {

    private static final String ENDPOINT = "POST /api/v1/orders";
    private static final Instant NOW = Instant.parse("2026-07-31T10:15:30Z");

    @Autowired
    private IdempotencyRepository repository;

    /** idempotency_records.merchant_id is foreign-keyed, so a tenant has to exist first. */
    @Autowired
    private MerchantRepository merchants;

    @Test
    void insertsOnceAndRefusesTheSameScopeThereafter() {
        IdempotencyRecord attempt = attempt(existingMerchant(), key(), "{}");

        assertThat(repository.insertIfAbsent(attempt)).isTrue();
        assertThat(repository.insertIfAbsent(attempt)).isFalse();
    }

    /**
     * The losing insert must be visible to the loser immediately, because that is what turns a
     * refused insert into a replay rather than a duplicate.
     */
    @Test
    void makesTheInsertedRecordReadableAtOnce() {
        MerchantId merchantId = existingMerchant();
        String key = key();
        repository.insertIfAbsent(attempt(merchantId, key, "{}"));

        IdempotencyRecord found = repository.findBy(merchantId, ENDPOINT, key).orElseThrow();

        assertThat(found.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(found.merchantId()).isEqualTo(merchantId);
        assertThat(found.endpoint()).isEqualTo(ENDPOINT);
        assertThat(found.idempotencyKey()).isEqualTo(key);
        assertThat(found.requestHash()).isEqualTo(hash("{}"));
        assertThat(found.createdAt()).isEqualTo(NOW);
        assertThat(found.responseStatus()).isNull();
        assertThat(found.responseBody()).isNull();
        assertThat(found.completedAt()).isNull();
    }

    @Test
    void returnsEmptyForAKeyNobodyHasUsed() {
        assertThat(repository.findBy(existingMerchant(), ENDPOINT, key())).isEmpty();
    }

    /** The scope leads with the merchant, so the same key under two tenants is two rows. */
    @Test
    void scopesTheKeyToTheMerchant() {
        String key = key();

        assertThat(repository.insertIfAbsent(attempt(existingMerchant(), key, "{}"))).isTrue();
        assertThat(repository.insertIfAbsent(attempt(existingMerchant(), key, "{}"))).isTrue();
    }

    /** The same key aimed at a different endpoint is a different scope. */
    @Test
    void scopesTheKeyToTheEndpoint() {
        MerchantId merchantId = existingMerchant();
        String key = key();

        assertThat(repository.insertIfAbsent(attempt(merchantId, key, "{}"))).isTrue();
        assertThat(repository.insertIfAbsent(
            IdempotencyRecord.started(merchantId, "POST /api/v1/orders/{orderId}/cancel", key, hash("{}"), NOW)
        )).isTrue();
    }

    @Test
    void storesTheResponseWhenTheAttemptCompletes() {
        MerchantId merchantId = existingMerchant();
        String key = key();
        IdempotencyRecord attempt = attempt(merchantId, key, "{}");
        repository.insertIfAbsent(attempt);

        repository.complete(attempt.completedWith(201, "{\"id\":\"ord_1\"}", NOW.plusSeconds(1)));

        IdempotencyRecord found = repository.findBy(merchantId, ENDPOINT, key).orElseThrow();
        assertThat(found.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(found.responseStatus()).isEqualTo(201);
        assertThat(found.responseBody()).isEqualTo("{\"id\":\"ord_1\"}");
        assertThat(found.completedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    /** A 204 stores no body; the pairing CHECK keys off the status, not the body. */
    @Test
    void storesACompletedResponseWithNoBody() {
        MerchantId merchantId = existingMerchant();
        String key = key();
        IdempotencyRecord attempt = attempt(merchantId, key, "{}");
        repository.insertIfAbsent(attempt);

        repository.complete(attempt.completedWith(204, null, NOW));

        assertThat(repository.findBy(merchantId, ENDPOINT, key).orElseThrow().responseBody()).isNull();
    }

    /** Deleting is what makes a retry after a 5xx a real retry rather than a replay. */
    @Test
    void freesTheKeyAgainAfterDeletion() {
        MerchantId merchantId = existingMerchant();
        String key = key();
        IdempotencyRecord attempt = attempt(merchantId, key, "{}");
        repository.insertIfAbsent(attempt);

        repository.delete(merchantId, ENDPOINT, key);

        assertThat(repository.findBy(merchantId, ENDPOINT, key)).isEmpty();
        assertThat(repository.insertIfAbsent(attempt)).isTrue();
    }

    private static IdempotencyRecord attempt(MerchantId merchantId, String key, String body) {
        return IdempotencyRecord.started(merchantId, ENDPOINT, key, hash(body), NOW);
    }

    private static String hash(String body) {
        return IdempotencyRecord.hashOf(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            Instant.now()
        )).merchantId();
    }
}
