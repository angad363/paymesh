package com.paymesh.settlement.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementBatchTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    @Test
    void markPostedMovesAPendingBatchToPosted() {
        SettlementBatch posted = pending().markPosted(NOW);

        assertThat(posted.status()).isEqualTo(SettlementBatchStatus.POSTED);
    }

    @Test
    void markPostedOnAnAlreadyPostedBatchIsAnIdempotentNoOp() {
        SettlementBatch once = pending().markPosted(NOW);

        assertThat(once.markPosted(NOW).status()).isEqualTo(SettlementBatchStatus.POSTED);
    }

    /**
     * The ledger posting a batch settlement already returned is a real disagreement between the two
     * sides, not a redelivery. Silently accepting it would hide that, so the state machine refuses it.
     */
    @Test
    void markPostedRefusesABatchAlreadyReturned() {
        SettlementBatch returned = pending().markReturned(NOW);

        assertThatThrownBy(() -> returned.markPosted(NOW))
            .isInstanceOf(SettlementBatchNotPendingException.class);
    }

    private static SettlementBatch pending() {
        return SettlementBatch.cut(MerchantId.generate(), "GBP", List.of(SettlementItem.of("pay_x", 100)), NOW);
    }
}
