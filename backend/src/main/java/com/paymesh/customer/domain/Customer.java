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

    /**
     * The merchant blocks their own buyer.
     *
     * <h2>{@code BLOCKED} WAS DECLARED AND UNREACHABLE</h2>
     *
     * {@code CustomerStatus} has had two values since V3 and only ACTIVE was ever produced -- one
     * of the three lifecycle enums the Phase 1 audit found frozen. ADR-021.
     *
     * <h2>Blocking is the merchant's decision, not the platform's</h2>
     *
     * Unlike a merchant suspension, PayMesh has no opinion here: this is a business deciding it
     * will not sell to someone. So the actor is MERCHANT, and a merchant-scoped caller may do it
     * to their own customers and nobody else's.
     * <p>
     * What it stops is <b>new collection</b> -- an order or a payment intent naming a blocked
     * customer is refused. It deliberately does NOT touch money already in flight: a payment that
     * is already PROCESSING settles normally, because a customer who has been charged is owed an
     * outcome whatever the merchant now thinks of them, and a refund of an existing payment must
     * stay possible for exactly the same reason.
     */
    public Customer block(Instant blockedAt) {
        requireStatusChange(CustomerStatus.BLOCKED, blockedAt);

        return withStatus(CustomerStatus.BLOCKED, blockedAt);
    }

    /** Reversible, unlike a merchant closure: a blocked buyer is a commercial decision. */
    public Customer unblock(Instant unblockedAt) {
        requireStatusChange(CustomerStatus.ACTIVE, unblockedAt);

        return withStatus(CustomerStatus.ACTIVE, unblockedAt);
    }

    /**
     * Correct the contact details. SDD 10.3's PATCH.
     *
     * <h2>NULL MEANS "LEAVE IT ALONE", AND THE HASHES MOVE WITH THE VALUES</h2>
     *
     * {@code email_hash} and {@code phone_hash} are what the indexes are on -- the plaintext
     * columns are display-only, which is the shape encryption would need (ADR-006). Updating an
     * email without recomputing its hash would leave the row findable by its OLD address and
     * invisible under its new one, and nothing would report an error.
     *
     * <h2>The merchant reference is not editable</h2>
     *
     * It is the merchant's own key for this customer, unique per merchant, and very likely the
     * join key in their system. Changing it is creating a different customer.
     */
    public Customer updateContact(String newEmail, String newName, String newPhone, Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("A customer update needs an instant");
        }

        String email = newEmail == null ? this.email : normalizeEmail(newEmail);
        String name = newName == null ? this.name : normalizeName(newName);
        String phone = newPhone == null ? this.phone : normalizePhone(newPhone);

        return new Customer(
            customerId, merchantId, merchantReference,
            email, LookupHash.of(email),
            name,
            phone, LookupHash.of(phone),
            status, createdAt, at
        );
    }

    /** True when this customer may be named on a new order or payment intent. */
    public boolean canBeCharged() {
        return status == CustomerStatus.ACTIVE;
    }

    private Customer withStatus(CustomerStatus next, Instant at) {
        return new Customer(
            customerId, merchantId, merchantReference, email, emailHash, name, phone, phoneHash,
            next, createdAt, at
        );
    }

    private void requireStatusChange(CustomerStatus next, Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("A customer status change needs an instant");
        }

        if (status == next) {
            throw new CustomerStatusNotChangeableException(customerId, status, next);
        }
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
