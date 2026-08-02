package com.paymesh.merchant.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The state machine that did not exist.
 * <p>
 * {@code Merchant} had no state-changing method at all -- only {@code register}, {@code
 * reconstitute} and getters -- while CLAUDE.md cited {@code merchant.activate()} twice as the
 * canonical example of the intent-method convention. Every merchant was permanently
 * PENDING_VERIFICATION and nothing anywhere read the status. ADR-021.
 */
class MerchantLifecycleTest {

    private static final Instant REGISTERED = Instant.parse("2026-08-02T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-02T11:00:00Z");

    @Test
    void registersPendingVerification() {
        assertThat(merchant().status()).isEqualTo(MerchantStatus.PENDING_VERIFICATION);
        assertThat(merchant().canTransact())
            .as("and an unverified merchant may not write")
            .isFalse();
    }

    @Test
    void activatesFromPendingVerification() {
        Merchant active = merchant().activate(LATER);

        assertThat(active.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(active.canTransact()).isTrue();
        assertThat(active.updatedAt()).isEqualTo(LATER);
    }

    /** THE CONTROL THE PLATFORM DID NOT HAVE: a merchant that can be stopped. */
    @Test
    void suspendsAnActiveMerchantAndStopsItTransacting() {
        Merchant suspended = merchant().activate(LATER).suspend(LATER);

        assertThat(suspended.status()).isEqualTo(MerchantStatus.SUSPENDED);
        assertThat(suspended.canTransact()).isFalse();
    }

    /** Reversible, which is what makes suspension safe to use during an investigation. */
    @Test
    void reinstatesASuspendedMerchant() {
        Merchant reinstated = merchant().activate(LATER).suspend(LATER).activate(LATER);

        assertThat(reinstated.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(reinstated.canTransact()).isTrue();
    }

    /** Terminal. A reversible closure is just a suspension with a more alarming name. */
    @Test
    void closesTerminally() {
        Merchant closed = merchant().activate(LATER).close(LATER);

        assertThat(closed.canTransact()).isFalse();

        assertThatThrownBy(() -> closed.activate(LATER))
            .isInstanceOf(MerchantStatusNotChangeableException.class);
        assertThatThrownBy(() -> closed.suspend(LATER))
            .isInstanceOf(MerchantStatusNotChangeableException.class);
    }

    /** An abandoned or rejected registration is closed without ever having been activated. */
    @Test
    void closesAMerchantThatWasNeverActivated() {
        assertThat(merchant().close(LATER).status()).isEqualTo(MerchantStatus.CLOSED);
    }

    @Test
    void refusesToSuspendAMerchantThatWasNeverActivated() {
        assertThatThrownBy(() -> merchant().suspend(LATER))
            .isInstanceOf(MerchantStatusNotChangeableException.class)
            .hasMessageContaining("PENDING_VERIFICATION");
    }

    @Test
    void refusesARedundantActivation() {
        Merchant active = merchant().activate(LATER);

        assertThatThrownBy(() -> active.activate(LATER))
            .isInstanceOf(MerchantStatusNotChangeableException.class);
    }

    /** A status change that predates the registration would corrupt the timeline's ordering. */
    @Test
    void refusesAChangeBeforeTheRegistration() {
        assertThatThrownBy(() -> merchant().activate(REGISTERED.minusSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("predate");
    }

    @Test
    void renamesWithoutTouchingStatus() {
        Merchant renamed = merchant().activate(LATER).rename("  New Name  ", LATER);

        assertThat(renamed.businessName()).isEqualTo("New Name");
        assertThat(renamed.status()).isEqualTo(MerchantStatus.ACTIVE);
    }

    // --- the audit record -------------------------------------------------------------------

    /**
     * A SUSPENSION WITH NO STATED REASON IS NOT AN AUDIT TRAIL, and
     * {@code ck_merchant_status_history_reason} says the same thing at the schema.
     */
    @Test
    void refusesASuspensionWithNoReason() {
        assertThatThrownBy(() -> new MerchantStatusChange(
            MerchantId.generate(), MerchantStatus.ACTIVE, MerchantStatus.SUSPENDED,
            MerchantStatusChange.ActorType.PLATFORM, "usr_1", null, LATER
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reason");
    }

    /** There is no MERCHANT actor: a merchant lifting its own suspension would make it advisory. */
    @Test
    void requiresAnOperatorOnAPlatformChange() {
        assertThatThrownBy(() -> new MerchantStatusChange(
            MerchantId.generate(), MerchantStatus.PENDING_VERIFICATION, MerchantStatus.ACTIVE,
            MerchantStatusChange.ActorType.PLATFORM, null, null, LATER
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("operator");
    }

    /** A timer has nobody to name, and recording one in an audit table would be a lie. */
    @Test
    void refusesAnActorOnASystemChange() {
        assertThatThrownBy(() -> new MerchantStatusChange(
            MerchantId.generate(), MerchantStatus.PENDING_VERIFICATION, MerchantStatus.ACTIVE,
            MerchantStatusChange.ActorType.SYSTEM, "usr_1", null, LATER
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nobody to name");
    }

    private static Merchant merchant() {
        return Merchant.register(
            MerchantId.generate(), "Test Co", "test@paymesh.test", "IN", "INR", REGISTERED
        );
    }
}
