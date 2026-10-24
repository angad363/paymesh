package com.paymesh.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-003's prefixed identifiers, for the two the ledger introduces. */
class LedgerIdTest {

    @Test
    void mintsAccountIdentifiersWithTheLacPrefix() {
        assertThat(LedgerAccountId.generate().value()).startsWith("lac_");
    }

    @Test
    void mintsTransactionIdentifiersWithTheLtxPrefix() {
        assertThat(LedgerTransactionId.generate().value()).startsWith("ltx_");
    }

    /**
     * NEITHER PREFIX IS A PREFIX OF THE OTHER, which is why they are three letters rather than two.
     * A {@code la_}/{@code lt_} pair would still be distinct, but a truncated or concatenated id
     * could parse as the wrong type; this makes that impossible rather than unlikely.
     */
    @Test
    void refusesATransactionIdentifierWhereAnAccountIdentifierIsExpected() {
        String transactionId = LedgerTransactionId.generate().value();

        assertThatThrownBy(() -> LedgerAccountId.from(transactionId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must start with lac_");
    }

    @Test
    void refusesAnAccountIdentifierWhereATransactionIdentifierIsExpected() {
        String accountId = LedgerAccountId.generate().value();

        assertThatThrownBy(() -> LedgerTransactionId.from(accountId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must start with ltx_");
    }

    @Test
    void roundTripsAGeneratedAccountIdentifier() {
        LedgerAccountId generated = LedgerAccountId.generate();

        assertThat(LedgerAccountId.from(generated.value())).isEqualTo(generated);
    }

    @Test
    void refusesAnAccountIdentifierWithoutAValidUuid() {
        assertThatThrownBy(() -> LedgerAccountId.from("lac_not-a-uuid"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid UUID");
    }

    @Test
    void refusesANullAccountIdentifier() {
        assertThatThrownBy(() -> LedgerAccountId.from(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be null");
    }

    @Test
    void refusesABlankTransactionIdentifier() {
        assertThatThrownBy(() -> LedgerTransactionId.from("  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be blank");
    }
}
