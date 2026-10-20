package com.paymesh.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshTokenTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-07-18T10:15:30Z");
    private static final Instant EXPIRES_AT = ISSUED_AT.plus(Duration.ofDays(30));

    @Test
    void storesOnlyTheHashOfTheToken() {
        String plaintext = RefreshToken.newSecret();

        RefreshToken token = issue(plaintext);

        assertEquals(RefreshToken.hash(plaintext), token.tokenHash());
        assertNotEquals(plaintext, token.tokenHash());
        assertEquals(64, token.tokenHash().length());
    }

    @Test
    void hashesTheSameTokenToTheSameValue() {
        String plaintext = RefreshToken.newSecret();

        assertEquals(RefreshToken.hash(plaintext), RefreshToken.hash(plaintext));
    }

    @Test
    void mintsADistinctSecretEveryTime() {
        assertNotEquals(RefreshToken.newSecret(), RefreshToken.newSecret());
    }

    @Test
    void isSpendableWhileLiveAndUnrevoked() {
        RefreshToken token = issue(RefreshToken.newSecret());

        assertTrue(token.isSpendable(ISSUED_AT));
        assertFalse(token.isRevoked());
        assertFalse(token.isExpired(ISSUED_AT));
    }

    @Test
    void isNotSpendableOnceExpired() {
        RefreshToken token = issue(RefreshToken.newSecret());

        assertTrue(token.isExpired(EXPIRES_AT));
        assertFalse(token.isSpendable(EXPIRES_AT));
        assertFalse(token.isSpendable(EXPIRES_AT.plusSeconds(1)));
    }

    @Test
    void isNotSpendableOnceRevoked() {
        RefreshToken revoked = issue(RefreshToken.newSecret()).revoke(ISSUED_AT.plusSeconds(60));

        assertTrue(revoked.isRevoked());
        assertFalse(revoked.isSpendable(ISSUED_AT.plusSeconds(61)));
    }

    /** The first revocation is when the session ended; reprocessing must not move it. */
    @Test
    void keepsTheFirstRevocationInstant() {
        Instant firstRevocation = ISSUED_AT.plusSeconds(60);

        RefreshToken revoked = issue(RefreshToken.newSecret()).revoke(firstRevocation);
        RefreshToken revokedAgain = revoked.revoke(ISSUED_AT.plusSeconds(120));

        assertSame(revoked, revokedAgain);
        assertEquals(firstRevocation, revokedAgain.revokedAt());
    }

    @Test
    void rejectsExpiryBeforeIssuance() {
        assertThrows(
            IllegalArgumentException.class,
            () -> RefreshToken.issue(
                RefreshToken.newFamilyId(),
                UserId.generate(),
                RefreshToken.newSecret(),
                ISSUED_AT,
                ISSUED_AT.minusSeconds(1)
            )
        );
    }

    @Test
    void rejectsBlankToken() {
        assertThrows(
            IllegalArgumentException.class,
            () -> RefreshToken.issue(
                RefreshToken.newFamilyId(),
                UserId.generate(),
                "  ",
                ISSUED_AT,
                EXPIRES_AT
            )
        );
    }

    private static RefreshToken issue(String plaintext) {
        return RefreshToken.issue(
            RefreshToken.newFamilyId(),
            UserId.generate(),
            plaintext,
            ISSUED_AT,
            EXPIRES_AT
        );
    }
}
