package com.paymesh.merchant.domain;

import java.time.Instant;
import java.util.Locale;

public final class Merchant {
    private final MerchantId merchantId;
    private final String businessName;
    private final String email;
    private final String country;
    private final String defaultCurrency;
    private final MerchantStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Merchant(
        MerchantId merchantId,
        String businessName,
        String email,
        String country,
        String defaultCurrency,
        MerchantStatus status,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.merchantId = merchantId;
        this.businessName = businessName;
        this.email = email;
        this.country = country;
        this.defaultCurrency = defaultCurrency;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Merchant register(
        MerchantId merchantId,
        String businessName,
        String email,
        String country,
        String defaultCurrency,
        Instant registeredAt
    ) {
        MerchantId validatedMerchantId = requireMerchantId(merchantId);
        String normalizedBusinessName = normalizeBusinessName(businessName);
        Instant validatedRegisteredAt = requireRegistrationTimestamp(registeredAt);
        String normalizedEmail = normalizeEmail(email);
        String normalizedCountry = normalizeCountry(country);
        String normalizedDefaultCurrency = normalizeDefaultCurrency(defaultCurrency);

        return new Merchant(
            validatedMerchantId,
            normalizedBusinessName,
            normalizedEmail,
            normalizedCountry,
            normalizedDefaultCurrency,
            MerchantStatus.PENDING_VERIFICATION,
            validatedRegisteredAt,
            validatedRegisteredAt
        );
    }

    private static String normalizeBusinessName(String businessName) {
        if(businessName == null) {
            throw new IllegalArgumentException("Business name cannot be null");
        }

        String normalizedBusinessName = businessName.trim();

        if(normalizedBusinessName.isBlank()) {
            throw new IllegalArgumentException("Business name cannot be blank");
        }

        if(normalizedBusinessName.length() > 200) {
            throw new IllegalArgumentException("Business name cannot be longer than 200 characters");
        }

        return normalizedBusinessName;
    }

    private static MerchantId requireMerchantId(MerchantId merchantId) {
        if(merchantId == null) {
            throw new IllegalArgumentException("Merchant Identifier cannot be null");
        }

        return merchantId;
    }

    private static Instant requireRegistrationTimestamp(Instant registeredAt) {
        if(registeredAt == null) {
            throw new IllegalArgumentException("Registration timestamp cannot be null");
        }

        return registeredAt;
    }

    private static String normalizeEmail(String email) {
        if(email == null) {
            throw new IllegalArgumentException("Email cannot be null");
        }

        String normalizedEmail = email.trim();

        if(normalizedEmail.isBlank()) {
            throw new IllegalArgumentException("Email cannot be blank");
        }

        return normalizedEmail.toLowerCase(Locale.ROOT);
    }

    private static String normalizeCountry(String country) {
        if(country == null) {
            throw new IllegalArgumentException("Country cannot be null");
        }

        String normalizedCountry = country.trim().toUpperCase(Locale.ROOT);

        if(normalizedCountry.isBlank()) {
            throw new IllegalArgumentException("Country cannot be blank");
        }

        if(!normalizedCountry.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("Country must be of length 2");
        }

        return normalizedCountry;
    }

    private static String normalizeDefaultCurrency(String defaultCurrency) {
        if(defaultCurrency == null) {
            throw new IllegalArgumentException("Default currency cannot be null");
        }

        String normalizedDefaultCurrency = defaultCurrency.trim().toUpperCase(Locale.ROOT);

        if(normalizedDefaultCurrency.isBlank()) {
            throw new IllegalArgumentException("Default currency cannot be blank");
        }

        if(!normalizedDefaultCurrency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Default currency must be of length 3");
        }

        return normalizedDefaultCurrency;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public String businessName() {
        return businessName;
    }

    public String email() {
        return email;
    }

    public String country() {
        return country;
    }

    public String defaultCurrency() {
        return defaultCurrency;
    }

    public MerchantStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
