package com.paymesh.merchant.domain;

import com.paymesh.shared.tenant.MerchantId;

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

    /**
     * Rebuilds a Merchant from already-persisted state. Deliberately does NOT re-normalize: those
     * values passed through register() before they were stored, so re-normalizing on read would
     * mask corruption. Unlike register(), it can restore any status and a distinct updatedAt.
     */
    public static Merchant reconstitute(
        MerchantId merchantId,
        String businessName,
        String email,
        String country,
        String defaultCurrency,
        MerchantStatus status,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new Merchant(
            merchantId,
            businessName,
            email,
            country,
            defaultCurrency,
            status,
            createdAt,
            updatedAt
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

    /**
     * PENDING_VERIFICATION to ACTIVE, when a platform operator approves verification.
     *
     * <h2>THIS METHOD DID NOT EXIST, AND CLAUDE.md CITED IT TWICE</h2>
     *
     * {@code merchant.activate()} was the repository's canonical example of the intent-method
     * convention -- named in CLAUDE.md's architecture rules and again in its change conventions --
     * and the aggregate had no state-changing method at all. Every merchant was permanently
     * PENDING_VERIFICATION, nothing read the status, and there was no way to stop a merchant
     * trading. ADR-021.
     *
     * <h2>Re-activating a SUSPENDED merchant is the same transition</h2>
     *
     * Deliberately one method rather than {@code activate()} plus {@code reinstate()}: the
     * resulting state is identical, the authority required is identical, and two methods would
     * invite two subtly different sets of rules for the same outcome. CLOSED is the exception --
     * see {@link #close}.
     */
    public Merchant activate(Instant activatedAt) {
        requireTransitionTo(MerchantStatus.ACTIVE, activatedAt);

        return withStatus(MerchantStatus.ACTIVE, activatedAt);
    }

    /**
     * Stop this merchant trading, reversibly.
     * <p>
     * THE CONTROL THE PLATFORM DID NOT HAVE. A suspended merchant fails every authenticated write
     * -- orders, intents, captures, refunds -- at the boundary, before any handler runs. Reads
     * still work, because a suspended merchant reconciling what happened to them is not a threat
     * and blocking it only makes the incident harder to resolve.
     */
    public Merchant suspend(Instant suspendedAt) {
        requireTransitionTo(MerchantStatus.SUSPENDED, suspendedAt);

        return withStatus(MerchantStatus.SUSPENDED, suspendedAt);
    }

    /**
     * Terminal. A closed merchant cannot be reopened -- it registers again.
     * <p>
     * Terminal on purpose: closure is the state that should be reached only deliberately, and a
     * reversible closure is just a suspension with a more alarming name. Keeping them distinct is
     * what makes "suspended" safe to use freely during an investigation.
     */
    public Merchant close(Instant closedAt) {
        requireTransitionTo(MerchantStatus.CLOSED, closedAt);

        return withStatus(MerchantStatus.CLOSED, closedAt);
    }

    /**
     * Correct the presentational name. Normalized through the same rule as registration, because a
     * value that arrives by a different door must not be held to a different standard.
     */
    public Merchant rename(String newBusinessName, Instant renamedAt) {
        if (renamedAt == null) {
            throw new IllegalArgumentException("A merchant rename needs an instant");
        }

        return new Merchant(
            merchantId, normalizeBusinessName(newBusinessName), email, country, defaultCurrency,
            status, createdAt, renamedAt
        );
    }

    /** True when this merchant may perform authenticated writes. */
    public boolean canTransact() {
        return status == MerchantStatus.ACTIVE;
    }

    private Merchant withStatus(MerchantStatus next, Instant at) {
        return new Merchant(
            merchantId, businessName, email, country, defaultCurrency, next, createdAt, at
        );
    }

    /**
     * Every legal move, in one place.
     *
     * <pre>
     *   PENDING_VERIFICATION --&gt; ACTIVE | CLOSED
     *   ACTIVE               --&gt; SUSPENDED | CLOSED
     *   SUSPENDED            --&gt; ACTIVE | CLOSED
     *   CLOSED               --&gt; (nothing)
     * </pre>
     *
     * A PENDING_VERIFICATION merchant may be closed without ever being activated, which is what
     * happens to an abandoned or rejected registration.
     */
    private void requireTransitionTo(MerchantStatus next, Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("A merchant status change needs an instant");
        }

        if (at.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                "A merchant status change cannot predate the registration"
            );
        }

        boolean legal = switch (status) {
            case PENDING_VERIFICATION -> next == MerchantStatus.ACTIVE || next == MerchantStatus.CLOSED;
            case ACTIVE -> next == MerchantStatus.SUSPENDED || next == MerchantStatus.CLOSED;
            case SUSPENDED -> next == MerchantStatus.ACTIVE || next == MerchantStatus.CLOSED;
            case CLOSED -> false;
        };

        if (!legal) {
            throw new MerchantStatusNotChangeableException(merchantId, status, next);
        }
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
