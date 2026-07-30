package com.paymesh.customer.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Locale;

/**
 * A buyer profile owned by exactly one merchant.
 * <p>
 * The merchantId is not decoration: it is the tenant boundary. A Customer cannot exist without one,
 * and nothing downstream may read a customer without also naming the merchant it belongs to.
 * <p>
 * Email and phone are each stored twice: the value itself (for display) and a deterministic hash
 * (for exact-match lookup). Today both are plaintext; the split exists so encryption can replace the
 * display values without touching queries or indexes. See ADR-006.
 */
public final class Customer {
    private static final int MERCHANT_REFERENCE_MAX_LENGTH = 100;
    private static final int EMAIL_MAX_LENGTH = 320;
    private static final int NAME_MAX_LENGTH = 200;
    private static final int PHONE_MAX_LENGTH = 32;

    private final CustomerId customerId;
    private final MerchantId merchantId;
    private final String merchantReference;
    private final String email;
    private final String emailHash;
    private final String name;
    private final String phone;
    private final String phoneHash;
    private final CustomerStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Customer(
        CustomerId customerId,
        MerchantId merchantId,
        String merchantReference,
        String email,
        String emailHash,
        String name,
        String phone,
        String phoneHash,
        CustomerStatus status,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.customerId = customerId;
        this.merchantId = merchantId;
        this.merchantReference = merchantReference;
        this.email = email;
        this.emailHash = emailHash;
        this.name = name;
        this.phone = phone;
        this.phoneHash = phoneHash;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Customer create(
        CustomerId customerId,
        MerchantId merchantId,
        String merchantReference,
        String email,
        String name,
        String phone,
        Instant createdAt
    ) {
        CustomerId validatedCustomerId = requireCustomerId(customerId);
        MerchantId validatedMerchantId = requireMerchantId(merchantId);
        Instant validatedCreatedAt = requireCreationTimestamp(createdAt);
        String normalizedMerchantReference = normalizeMerchantReference(merchantReference);
        String normalizedEmail = normalizeEmail(email);
        String normalizedName = normalizeName(name);
        String normalizedPhone = normalizePhone(phone);

        return new Customer(
            validatedCustomerId,
            validatedMerchantId,
            normalizedMerchantReference,
            normalizedEmail,
            LookupHash.of(normalizedEmail),
            normalizedName,
            normalizedPhone,
            LookupHash.of(normalizedPhone),
            CustomerStatus.ACTIVE,
            validatedCreatedAt,
            validatedCreatedAt
        );
    }

    /**
     * Rebuilds a Customer from already-persisted state. Deliberately does NOT re-normalize or
     * re-hash: those values passed through create() before they were stored, so recomputing on read
     * would mask corruption rather than repair it. Unlike create(), it can restore any status and an
     * updatedAt distinct from createdAt.
     */
    public static Customer reconstitute(
        CustomerId customerId,
        MerchantId merchantId,
        String merchantReference,
        String email,
        String emailHash,
        String name,
        String phone,
        String phoneHash,
        CustomerStatus status,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new Customer(
            customerId,
            merchantId,
            merchantReference,
            email,
            emailHash,
            name,
            phone,
            phoneHash,
            status,
            createdAt,
            updatedAt
        );
    }

    private static CustomerId requireCustomerId(CustomerId customerId) {
        if(customerId == null) {
            throw new IllegalArgumentException("Customer Identifier cannot be null");
        }

        return customerId;
    }

    private static MerchantId requireMerchantId(MerchantId merchantId) {
        if(merchantId == null) {
            throw new IllegalArgumentException("Merchant Identifier cannot be null");
        }

        return merchantId;
    }

    private static Instant requireCreationTimestamp(Instant createdAt) {
        if(createdAt == null) {
            throw new IllegalArgumentException("Creation timestamp cannot be null");
        }

        return createdAt;
    }

    private static String normalizeEmail(String email) {
        if(email == null) {
            throw new IllegalArgumentException("Email cannot be null");
        }

        String normalizedEmail = email.trim();

        if(normalizedEmail.isBlank()) {
            throw new IllegalArgumentException("Email cannot be blank");
        }

        if(normalizedEmail.length() > EMAIL_MAX_LENGTH) {
            throw new IllegalArgumentException("Email cannot be longer than " + EMAIL_MAX_LENGTH + " characters");
        }

        return normalizedEmail.toLowerCase(Locale.ROOT);
    }

    /**
     * The merchant's own identifier for this customer. Optional, so an absent value and a value of
     * whitespace mean the same thing and both become null -- otherwise "  " and null would be two
     * different rows under a unique constraint that treats only one of them as absent.
     */
    private static String normalizeMerchantReference(String merchantReference) {
        return normalizeOptional(merchantReference, MERCHANT_REFERENCE_MAX_LENGTH, "Merchant reference");
    }

    private static String normalizeName(String name) {
        return normalizeOptional(name, NAME_MAX_LENGTH, "Name");
    }

    private static String normalizePhone(String phone) {
        return normalizeOptional(phone, PHONE_MAX_LENGTH, "Phone");
    }

    private static String normalizeOptional(String value, int maxLength, String fieldName) {
        if(value == null) {
            return null;
        }

        String normalizedValue = value.trim();

        if(normalizedValue.isBlank()) {
            return null;
        }

        if(normalizedValue.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " cannot be longer than " + maxLength + " characters");
        }

        return normalizedValue;
    }

    public CustomerId customerId() {
        return customerId;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public String merchantReference() {
        return merchantReference;
    }

    public String email() {
        return email;
    }

    public String emailHash() {
        return emailHash;
    }

    public String name() {
        return name;
    }

    public String phone() {
        return phone;
    }

    public String phoneHash() {
        return phoneHash;
    }

    public CustomerStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
