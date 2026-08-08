package com.paymesh.shared.tenant;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class MerchantIdTest {

    @Test
    void generateMerchantIdentifierWithExpectedPrefix() {
        MerchantId merchantId = MerchantId.generate();

        assertTrue(merchantId.value().startsWith("mrc_"));
    }

    @Test
    void generatedIdentifierContainsValidUuidSuffix() {
        MerchantId merchantId = MerchantId.generate();

        String uuidPart = merchantId.value().substring("mrc_".length());

        UUID.fromString(uuidPart);
    }

    @Test
    void generatesDifferentMerchantIdentifier() {
        MerchantId firstMerchatId = MerchantId.generate();
        MerchantId secondMerchantId = MerchantId.generate();

        assertNotEquals(firstMerchatId, secondMerchantId);
    }

    @Test
    void parsesValidMerchantIdentifier() {
        String value = "mrc_550e8400-e29b-41d4-a716-446655440000";
        MerchantId merchantId = MerchantId.from(value);

        assertEquals(value, merchantId.value());
    }

    @Test
    void preservesIdentifierValueWhenParsed() {
        String value = "mrc_f47ac10b-58cc-4372-a567-0e02b2c3d479";

        MerchantId merchantId = MerchantId.from(value);

        assertEquals(value, merchantId.toString());
    }

    @Test
    void rejectsIdentifierWithWrongResourcePrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> MerchantId.from(
                "cus_550e8400-e29b-41d4-a716-446655440000"
            )
        );
    }

    @Test
    void rejectsIdentifierWithoutPrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> MerchantId.from(
                "550e8400-e29b-41d4-a716-446655440000"
            )
        );
    }

    @Test
    void rejectsMalformedUuidSuffix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> MerchantId.from("mrc_not-a-uuid")
        );
    }

    @Test
    void rejectsBlankIdentifier() {
        assertThrows(
            IllegalArgumentException.class,
            () -> MerchantId.from("   ")
        );
    }

    @Test
    void rejectsNullIdentifier() {
        assertThrows(
            IllegalArgumentException.class,
            () -> MerchantId.from(null)
        );
    }

    /**
     * ONE UUID MUST HAVE ONE SPELLING, because this value is a primary key.
     *
     * <p>{@code UUID.fromString} is lenient -- it parses uppercase hex quite happily and returns
     * the canonical lowercase form. The round-trip check used {@code equalsIgnoreCase}, so
     * {@code mrc_550E8400-...} and {@code mrc_550e8400-...} were both accepted: two different
     * strings, one UUID, and therefore two rows for one merchant with nothing to stop it.
     *
     * <p>Found by V26 (ADR-029), which constrains these columns to canonical lowercase and was
     * therefore <b>stricter than the type it was supposed to mirror</b> -- the migration and the
     * ADR both asserted this case was already rejected, and it was not.
     */
    @Test
    void rejectsAnUppercaseUuidBecauseItIsASecondSpellingOfOneIdentifier() {
        assertThrows(
            IllegalArgumentException.class,
            () -> MerchantId.from("mrc_550E8400-E29B-41D4-A716-446655440000")
        );

        // The same value in canonical form is the one true spelling, and it still works.
        assertEquals(
            "mrc_550e8400-e29b-41d4-a716-446655440000",
            MerchantId.from("mrc_550e8400-e29b-41d4-a716-446655440000").value()
        );
    }
}
