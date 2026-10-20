package com.paymesh.merchant.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The identifier that was the loosest of the eighteen, and the reason V26 needed a matching fix in
 * Java rather than only a CHECK in the database.
 *
 * <p>{@code ApiCredentialId}, {@code KycSubmissionId} and {@code PaymentMethodTokenId} called
 * {@code UUID.fromString} and <b>threw the result away</b>. That is a parse, not a validation:
 * {@code UUID.fromString} accepts uppercase hex, and it accepts padded shorthand like
 * {@code "1-1-1-1-1"}, canonicalising both into a real UUID it then discarded. So every one of
 * those spellings was a legal identifier as far as the domain was concerned, while V26's CHECK
 * accepts only the canonical lowercase form.
 *
 * <p>These are primary keys. Two accepted spellings of one UUID is two rows for one thing.
 */
class ApiCredentialIdTest {

    private static final String CANONICAL = "apc_550e8400-e29b-41d4-a716-446655440000";

    @Test
    void generatesAnIdentifierThatItsOwnValidationAccepts() {
        ApiCredentialId generated = ApiCredentialId.generate();

        assertTrue(generated.value().startsWith("apc_"));
        assertEquals(generated, ApiCredentialId.from(generated.value()));
    }

    @Test
    void acceptsTheCanonicalLowercaseForm() {
        assertEquals(CANONICAL, ApiCredentialId.from(CANONICAL).value());
    }

    /** Parses fine, canonicalises to something else, and is therefore not this identifier. */
    @Test
    void rejectsAnUppercaseUuid() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ApiCredentialId.from("apc_550E8400-E29B-41D4-A716-446655440000")
        );
    }

    /**
     * The one a bare {@code UUID.fromString} call hides most completely: this parses to
     * {@code 00000001-0001-0001-0001-000000000001}, so "it parsed" says nothing about whether the
     * string was an identifier.
     */
    @Test
    void rejectsPaddedShorthandThatUuidFromStringWouldHappilyExpand() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ApiCredentialId.from("apc_1-1-1-1-1")
        );
    }

    @Test
    void rejectsTheWrongPrefix() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ApiCredentialId.from("mrc_550e8400-e29b-41d4-a716-446655440000")
        );
    }
}
