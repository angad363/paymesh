package com.paymesh.shared.idempotency.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRecordTest {

    private static final MerchantId MERCHANT_ID = MerchantId.generate();
    private static final Instant STARTED_AT = Instant.parse("2026-07-31T10:15:30Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-07-31T10:15:31Z");

    @Test
    void startsInProgressWithNoStoredResponse() {
        IdempotencyRecord record = started("body");

        assertThat(record.status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.responseStatus()).isNull();
        assertThat(record.responseBody()).isNull();
        assertThat(record.completedAt()).isNull();
        assertThat(record.isCompleted()).isFalse();
    }

    @Test
    void completingStoresTheResponseAndTheInstant() {
        IdempotencyRecord completed = started("body").completedWith(201, "{\"id\":\"ord_1\"}", FINISHED_AT);

        assertThat(completed.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(completed.responseStatus()).isEqualTo(201);
        assertThat(completed.responseBody()).isEqualTo("{\"id\":\"ord_1\"}");
        assertThat(completed.completedAt()).isEqualTo(FINISHED_AT);
        assertThat(completed.isCompleted()).isTrue();
    }

    /**
     * The pair of CHECK constraints in V4 expressed in Java. A half-written record replayed as a
     * real response is the failure mode this exists to make unrepresentable.
     */
    @Test
    void rejectsAnInProgressRecordThatCarriesAResponse() {
        assertThatThrownBy(() -> new IdempotencyRecord(
            MERCHANT_ID,
            "POST /api/v1/orders",
            "key-1",
            IdempotencyRecord.hashOf("body".getBytes(StandardCharsets.UTF_8)),
            IdempotencyStatus.IN_PROGRESS,
            201,
            "{}",
            STARTED_AT,
            FINISHED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsACompletedRecordWithNoResponseStatus() {
        assertThatThrownBy(() -> new IdempotencyRecord(
            MERCHANT_ID,
            "POST /api/v1/orders",
            "key-1",
            IdempotencyRecord.hashOf("body".getBytes(StandardCharsets.UTF_8)),
            IdempotencyStatus.COMPLETED,
            null,
            "{}",
            STARTED_AT,
            FINISHED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsARequestHashThatIsNotLowercaseHexSha256() {
        assertThatThrownBy(() -> new IdempotencyRecord(
            MERCHANT_ID,
            "POST /api/v1/orders",
            "key-1",
            "not-a-hash",
            IdempotencyStatus.IN_PROGRESS,
            null,
            null,
            STARTED_AT,
            null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankIdempotencyKey() {
        assertThatThrownBy(() -> started("body", "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Same bytes, same hash -- that is the whole of "is this the same request?". */
    @Test
    void hashesTheRawRequestBytes() {
        String empty = IdempotencyRecord.hashOf(new byte[0]);

        assertThat(empty)
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(IdempotencyRecord.hashOf("a".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo(IdempotencyRecord.hashOf("a".getBytes(StandardCharsets.UTF_8)))
            .isNotEqualTo(empty);
    }

    private static IdempotencyRecord started(String body) {
        return started(body, "key-1");
    }

    private static IdempotencyRecord started(String body, String key) {
        return IdempotencyRecord.started(
            MERCHANT_ID,
            "POST /api/v1/orders",
            key,
            IdempotencyRecord.hashOf(body.getBytes(StandardCharsets.UTF_8)),
            STARTED_AT
        );
    }
}
