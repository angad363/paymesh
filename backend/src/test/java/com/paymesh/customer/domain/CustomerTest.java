package com.paymesh.customer.domain;

import com.paymesh.merchant.domain.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomerTest {

    private static final MerchantId MERCHANT_ID = MerchantId.generate();
    private static final Instant CREATED_AT = Instant.parse("2026-07-19T10:15:30Z");

    @Test
    void createsAnActiveCustomerOwnedByTheGivenMerchant() {
        CustomerId customerId = CustomerId.generate();

        Customer customer = Customer.create(
            customerId,
            MERCHANT_ID,
            "user-42",
            "buyer@example.com",
            "Asha Rao",
            "+919876543210",
            CREATED_AT
        );

        assertSame(customerId, customer.customerId());
        assertSame(MERCHANT_ID, customer.merchantId());
        assertEquals("user-42", customer.merchantReference());
        assertEquals(CustomerStatus.ACTIVE, customer.status());
        assertEquals(CREATED_AT, customer.createdAt());
        assertEquals(CREATED_AT, customer.updatedAt());
    }

    @Test
    void normalizesEmailToTrimmedLowercase() {
        Customer customer = create("  Buyer@Example.COM  ");

        assertEquals("buyer@example.com", customer.email());
    }

    @Test
    void trimsOptionalTextFields() {
        Customer customer = Customer.create(
            CustomerId.generate(),
            MERCHANT_ID,
            "  user-42  ",
            "buyer@example.com",
            "  Asha Rao  ",
            "  +919876543210  ",
            CREATED_AT
        );

        assertEquals("user-42", customer.merchantReference());
        assertEquals("Asha Rao", customer.name());
        assertEquals("+919876543210", customer.phone());
    }

    /**
     * Blank and absent must collapse to the same value. Postgres treats NULLs as distinct under a
     * unique index, so if "   " survived as a reference it would be a second, non-null value that
     * the constraint happily allows twice.
     */
    @Test
    void treatsBlankOptionalFieldsAsAbsent() {
        Customer customer = Customer.create(
            CustomerId.generate(),
            MERCHANT_ID,
            "   ",
            "buyer@example.com",
            "   ",
            "   ",
            CREATED_AT
        );

        assertNull(customer.merchantReference());
        assertNull(customer.name());
        assertNull(customer.phone());
    }

    @Test
    void allowsAbsentOptionalFields() {
        Customer customer = Customer.create(
            CustomerId.generate(),
            MERCHANT_ID,
            null,
            "buyer@example.com",
            null,
            null,
            CREATED_AT
        );

        assertNull(customer.merchantReference());
        assertNull(customer.name());
        assertNull(customer.phone());
    }

    @Test
    void derivesADeterministicLookupHashFromTheNormalizedEmail() {
        Customer first = create("buyer@example.com");
        Customer second = create("  BUYER@Example.com ");

        assertEquals(first.emailHash(), second.emailHash());
        assertEquals(64, first.emailHash().length());
    }

    @Test
    void derivesDifferentLookupHashesForDifferentEmails() {
        assertNotEquals(create("one@example.com").emailHash(), create("two@example.com").emailHash());
    }

    @Test
    void derivesAPhoneLookupHashOnlyWhenAPhoneIsPresent() {
        Customer withPhone = Customer.create(
            CustomerId.generate(),
            MERCHANT_ID,
            null,
            "buyer@example.com",
            null,
            "+919876543210",
            CREATED_AT
        );

        assertEquals(64, withPhone.phoneHash().length());
        assertNull(create("buyer@example.com").phoneHash());
    }

    @Test
    void rejectsNullCustomerId() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Customer.create(null, MERCHANT_ID, null, "buyer@example.com", null, null, CREATED_AT)
        );
    }

    /**
     * The tenant boundary starts here: an ownerless customer must never be constructible.
     */
    @Test
    void rejectsNullMerchantId() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Customer.create(CustomerId.generate(), null, null, "buyer@example.com", null, null, CREATED_AT)
        );
    }

    @Test
    void rejectsNullCreationTimestamp() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Customer.create(CustomerId.generate(), MERCHANT_ID, null, "buyer@example.com", null, null, null)
        );
    }

    @Test
    void rejectsNullEmail() {
        assertThrows(IllegalArgumentException.class, () -> create(null));
    }

    @Test
    void rejectsBlankEmail() {
        assertThrows(IllegalArgumentException.class, () -> create("   "));
    }

    @Test
    void rejectsEmailLongerThanTheColumn() {
        assertThrows(IllegalArgumentException.class, () -> create("a".repeat(311) + "@example.com"));
    }

    @Test
    void rejectsMerchantReferenceLongerThanTheColumn() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Customer.create(
                CustomerId.generate(),
                MERCHANT_ID,
                "r".repeat(101),
                "buyer@example.com",
                null,
                null,
                CREATED_AT
            )
        );
    }

    @Test
    void rejectsNameLongerThanTheColumn() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Customer.create(
                CustomerId.generate(),
                MERCHANT_ID,
                null,
                "buyer@example.com",
                "n".repeat(201),
                null,
                CREATED_AT
            )
        );
    }

    @Test
    void rejectsPhoneLongerThanTheColumn() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Customer.create(
                CustomerId.generate(),
                MERCHANT_ID,
                null,
                "buyer@example.com",
                null,
                "9".repeat(33),
                CREATED_AT
            )
        );
    }

    /**
     * reconstitute restores stored state verbatim -- including a status create() cannot produce and
     * an updatedAt later than createdAt -- and does not re-normalize, so it cannot quietly repair
     * (and thereby hide) a corrupted row.
     */
    @Test
    void reconstitutesStoredStateWithoutRenormalizing() {
        CustomerId customerId = CustomerId.generate();
        Instant updatedAt = CREATED_AT.plusSeconds(600);

        Customer customer = Customer.reconstitute(
            customerId,
            MERCHANT_ID,
            "user-42",
            "  NotNormalized@Example.com  ",
            "storedhash",
            "Asha Rao",
            "+919876543210",
            "storedphonehash",
            CustomerStatus.BLOCKED,
            CREATED_AT,
            updatedAt
        );

        assertEquals("  NotNormalized@Example.com  ", customer.email());
        assertEquals("storedhash", customer.emailHash());
        assertEquals(CustomerStatus.BLOCKED, customer.status());
        assertEquals(updatedAt, customer.updatedAt());
    }

    private static Customer create(String email) {
        return Customer.create(CustomerId.generate(), MERCHANT_ID, null, email, null, null, CREATED_AT);
    }
}
