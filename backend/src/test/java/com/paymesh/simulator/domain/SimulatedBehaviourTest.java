package com.paymesh.simulator.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDD 13.6 wants deterministic test tokens; SDD 13.1 wants ambient error injection. Those pull in
 * opposite directions and this enum is the resolution: <b>the token is deterministic and wins; the
 * profile is ambient and applies only where the token asks for nothing.</b> These tests are that
 * sentence, executable.
 */
class SimulatedBehaviourTest {

    @Test
    void resolvesEachDeterministicTokenToItsOwnBehaviour() {
        assertThat(SimulatedBehaviour.resolve("tok_sim_success", SimulatedBehaviour.DECLINE))
            .isEqualTo(SimulatedBehaviour.SUCCEED);
        assertThat(SimulatedBehaviour.resolve("tok_sim_decline", SimulatedBehaviour.SUCCEED))
            .isEqualTo(SimulatedBehaviour.DECLINE);
        assertThat(SimulatedBehaviour.resolve("tok_sim_3ds", SimulatedBehaviour.SUCCEED))
            .isEqualTo(SimulatedBehaviour.REQUIRE_ACTION);
        assertThat(SimulatedBehaviour.resolve("tok_sim_timeout", SimulatedBehaviour.SUCCEED))
            .isEqualTo(SimulatedBehaviour.TIMEOUT);
        assertThat(SimulatedBehaviour.resolve("tok_sim_duplicate", SimulatedBehaviour.SUCCEED))
            .isEqualTo(SimulatedBehaviour.DUPLICATE_CALLBACK);
        assertThat(SimulatedBehaviour.resolve("tok_sim_stale", SimulatedBehaviour.SUCCEED))
            .isEqualTo(SimulatedBehaviour.STALE_CALLBACK);
    }

    /**
     * The half that matters most: a token that names a behaviour must not be overridden by the
     * profile. Without this, "make everything decline" would silently break every test that asks for
     * a specific outcome by token.
     */
    @Test
    void letsTheTokenWinOverTheAmbientDefault() {
        assertThat(SimulatedBehaviour.resolve("tok_sim_success", SimulatedBehaviour.DECLINE))
            .isEqualTo(SimulatedBehaviour.SUCCEED);
    }

    @Test
    void fallsBackToTheAmbientDefaultForAnUnrecognisedToken() {
        assertThat(SimulatedBehaviour.resolve("tok_whatever_the_merchant_uses", SimulatedBehaviour.DECLINE))
            .isEqualTo(SimulatedBehaviour.DECLINE);
    }

    /**
     * An unrecognised token is not an error, deliberately. A merchant integration under test sends
     * whatever token its own fixtures use, and refusing it would make this harder to point at than a
     * real provider -- which accepts any test instrument and decides for itself.
     */
    @Test
    void fallsBackToTheAmbientDefaultForABlankOrAbsentToken() {
        assertThat(SimulatedBehaviour.resolve(null, SimulatedBehaviour.SUCCEED))
            .isEqualTo(SimulatedBehaviour.SUCCEED);
        assertThat(SimulatedBehaviour.resolve("   ", SimulatedBehaviour.TIMEOUT))
            .isEqualTo(SimulatedBehaviour.TIMEOUT);
    }

    @Test
    void ignoresSurroundingWhitespaceAndCaseInAToken() {
        assertThat(SimulatedBehaviour.resolve("  TOK_SIM_DECLINE  ", SimulatedBehaviour.SUCCEED))
            .isEqualTo(SimulatedBehaviour.DECLINE);
    }

    @Test
    void refusesToResolveWithoutAnAmbientDefault() {
        assertThatThrownBy(() -> SimulatedBehaviour.resolve("tok_sim_success", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesTheWireValueOfAFailureProfileUpdate() {
        assertThat(SimulatedBehaviour.parse("  decline ")).isEqualTo(SimulatedBehaviour.DECLINE);
    }

    /**
     * The message must not name the Java enum class, which is what {@code valueOf} would leak. An
     * error body is not a place to hand a caller internal type names.
     */
    @Test
    void rejectsAnUnknownBehaviourWithoutNamingTheJavaType() {
        assertThatThrownBy(() -> SimulatedBehaviour.parse("EXPLODE"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown simulated behaviour: EXPLODE")
            .hasMessageNotContaining("SimulatedBehaviour.");
    }

    @Test
    void rejectsABlankBehaviour() {
        assertThatThrownBy(() -> SimulatedBehaviour.parse("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
