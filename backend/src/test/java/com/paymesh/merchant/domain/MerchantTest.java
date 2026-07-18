package com.paymesh.merchant.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MerchantTest {

    @Test
    void registersMerchantInPendingVerificationState() {
        MerchantId merchantId = MerchantId.from("mrc_550e8400-e29b-41d4-a716-446655440000");

        Instant registeredAt = Instant.parse("2026-07-18T10:15:30Z");

        Merchant merchant = Merchant.register(
            merchantId,
            "FreshBrew Cafe",
            "owner@freshbrew.example",
            "IN",
            "INR",
            registeredAt
        );

        assertEquals(
            MerchantStatus.PENDING_VERIFICATION,
            merchant.status()
        );

        assertEquals(
            merchantId,
            merchant.merchantId()
        );

        assertEquals(
            registeredAt,
            merchant.createdAt()
        );

        assertEquals(
            registeredAt,
            merchant.updatedAt()
        );
    }

    @Test
    void normalizesMerchantRegistrationDetails() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        Merchant merchant = Merchant.register(
            merchantId,
            "  FreshBrew Cafe  ",
            " Owner@FreshBrew.Example ",
            " in ",
            " inr ",
            registeredAt
        );

        assertEquals("FreshBrew Cafe", merchant.businessName());
        assertEquals("owner@freshbrew.example", merchant.email());
        assertEquals("IN", merchant.country());
        assertEquals("INR", merchant.defaultCurrency());
    }

    @Test
    void rejectsNullBusinessName() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                null,
                "owner@freshbrew.example",
                "IN",
                "INR",
                registeredAt
            )
        );
    }

    @Test
    void rejectsBlankBusinessName() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "   ",
                "owner@freshbrew.example",
                "IN",
                "INR",
                registeredAt
            )
        );
    }

    @Test
    void rejectsBusinessNameLongerThanTwoHundredCharacters() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        String longBusinessName = "a".repeat(201);

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                longBusinessName,
                "owner@freshbrew.example",
                "IN",
                "INR",
                registeredAt
            )
        );
    }

    @Test
    void rejectsNullMerchantIdentifier() {
        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                null,
                "FreshBrew Cafe",
                "owner@freshbrew.example",
                "IN",
                "INR",
                registeredAt
            )
        );
    }

    @Test
    void rejectsNullRegistrationTimestamp() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                "owner@freshbrew.example",
                "IN",
                "INR",
                null
            )
        );
    }

    @Test
    void rejectsNullEmail() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                null,
                "IN",
                "INR",
                registeredAt
            )
        );
    }

    @Test
    void rejectsBlankEmail() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                "   ",
                "IN",
                "INR",
                registeredAt
            )
        );
    }

    @Test
    void rejectsNullCountry() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                "owner@freshbrew.example",
                null,
                "INR",
                registeredAt
            )
        );
    }

    @Test
    void rejectsBlankCountry() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                "owner@freshbrew.example",
                "   ",
                "INR",
                registeredAt
            )
        );
    }

    @Test
    void rejectsCountryThatIsNotTwoLetters() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                "owner@freshbrew.example",
                "ARG",
                "ARS",
                registeredAt
            )
        );
    }

    @Test
    void rejectsCountryContainingNonLetters() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                "owner@freshbrew.example",
                "1N",
                "INR",
                registeredAt
            )
        );
    }

    @Test
    void rejectsNullDefaultCurrency() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                "owner@freshbrew.example",
                "IN",
                null,
                registeredAt
            )
        );
    }

    @Test
    void rejectsBlankDefaultCurrency() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                "owner@freshbrew.example",
                "IN",
                "   ",
                registeredAt
            )
        );
    }

    @Test
    void rejectsCurrencyThatIsNotThreeLetters() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                "owner@freshbrew.example",
                "IN",
                "IN",
                registeredAt
            )
        );
    }

    @Test
    void rejectsCurrencyContainingNonLetters() {
        MerchantId merchantId = MerchantId.from(
            "mrc_550e8400-e29b-41d4-a716-446655440000"
        );

        Instant registeredAt = Instant.parse(
            "2026-07-18T10:15:30Z"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> Merchant.register(
                merchantId,
                "FreshBrew Cafe",
                "owner@freshbrew.example",
                "IN",
                "IN1",
                registeredAt
            )
        );
    }
}
