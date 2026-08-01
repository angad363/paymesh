package com.paymesh.payment.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How an order is being collected: the amount, the capture policy, and where the collection has got
 * to. The order states the obligation; this states the attempt to meet it.
 * <p>
 * <b>A payment intent is operational state, not the financial record.</b> Reaching SUCCEEDED moves
 * no balance and posts no ledger entry -- the Ledger, when it exists, is the source of truth and
 * this aggregate does not know its schema exists.
 * <p>
 * The amount is a positive integer in MINOR UNITS with the currency held separately, and both are
 * immutable from creation. SDD 12.6 only requires immutability after confirmation; nothing
 * legitimately needs to change them before that either, and the stricter rule is the simpler one.
 * <p>
 * Instances are immutable: {@link #cancel} returns a new intent rather than mutating this one, and
 * there is deliberately no status setter. Callers request a transition and the state machine
 * decides whether it is legal.
 */
public final class PaymentIntent {

    /**
     * Restated from {@code Order}, not imported from it.
     * <p>
     * The two limits must agree -- an intent collects exactly its order's amount -- but importing
     * {@code com.paymesh.order.domain.Order} here would put a direct module dependency in Payment's
     * domain layer, which is precisely what the {@code OrderLookup} port exists to prevent
     * (ADR-008). The duplication is deliberate and the values are the same on purpose.
     */
    public static final long MAX_AMOUNT_MINOR = 999_999_999_999L;

    public static final int MAX_METADATA_ENTRIES = 16;
    public static final int MAX_METADATA_KEY_LENGTH = 40;
    public static final int MAX_METADATA_VALUE_LENGTH = 500;

    /**
     * The states a merchant may cancel from today.
     * <p>
     * It grows with the state machine: REQUIRES_CONFIRMATION when attach lands, REQUIRES_ACTION and
     * AUTHORIZED after that. <b>Every state a customer can strand an intent in must end up in this
     * set</b>, because {@code uq_payment_intents_live_per_order} frees an order's slot only on
     * FAILED or CANCELLED -- an uncancellable state is a dead order, which is worse than the
     * overpayment the index prevents.
     * <p>
     * PROCESSING is the deliberate exception and will never be added: an in-flight attempt may
     * already have succeeded at the provider, so cancelling locally could erase a payment that
     * really happened.
     */
    private static final Set<PaymentIntentStatus> CANCELLABLE =
        Set.of(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);

    private static final int CUSTOMER_REFERENCE_MAX_LENGTH = 40;
    private static final int ORDER_REFERENCE_MAX_LENGTH = 40;
    private static final int DESCRIPTION_MAX_LENGTH = 500;
    private static final int CANCELLATION_REASON_MAX_LENGTH = 200;
    private static final int CURRENCY_LENGTH = 3;

    private final PaymentIntentId paymentIntentId;
    private final MerchantId merchantId;
    private final String orderId;
    private final String customerId;
    private final long amountMinor;
    private final String currency;
    private final CaptureMethod captureMethod;
    private final PaymentIntentStatus status;
    private final long capturedAmountMinor;
    private final long refundedAmountMinor;
    private final String failureCode;
    private final String failureMessage;
    private final String cancellationReason;
    private final Instant cancelledAt;
    private final String description;
    private final Map<String, String> metadata;
    private final Integer version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private PaymentIntent(
        PaymentIntentId paymentIntentId,
        MerchantId merchantId,
        String orderId,
        String customerId,
        long amountMinor,
        String currency,
        CaptureMethod captureMethod,
        PaymentIntentStatus status,
        long capturedAmountMinor,
        long refundedAmountMinor,
        String failureCode,
        String failureMessage,
        String cancellationReason,
        Instant cancelledAt,
        String description,
        Map<String, String> metadata,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.paymentIntentId = paymentIntentId;
        this.merchantId = merchantId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.captureMethod = captureMethod;
        this.status = status;
        this.capturedAmountMinor = capturedAmountMinor;
        this.refundedAmountMinor = refundedAmountMinor;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.cancellationReason = cancellationReason;
        this.cancelledAt = cancelledAt;
        this.description = description;
        this.metadata = metadata;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static PaymentIntent create(
        PaymentIntentId paymentIntentId,
        MerchantId merchantId,
        String orderId,
        String customerId,
        long amountMinor,
        String currency,
        CaptureMethod captureMethod,
        String description,
        Map<String, String> metadata,
        Instant createdAt
    ) {
        return new PaymentIntent(
            requirePaymentIntentId(paymentIntentId),
            requireMerchantId(merchantId),
            requireOrderId(orderId),
            normalizeOptional(customerId, CUSTOMER_REFERENCE_MAX_LENGTH, "Customer identifier"),
            requireAmount(amountMinor),
            requireCurrency(currency),
            captureMethod == null ? CaptureMethod.AUTOMATIC : captureMethod,
            PaymentIntentStatus.REQUIRES_PAYMENT_METHOD,
            0,
            0,
            null,
            null,
            null,
            null,
            normalizeOptional(description, DESCRIPTION_MAX_LENGTH, "Description"),
            requireMetadata(metadata),
            // Never persisted yet, so there is no row version to preserve. The persistence adapter
            // reads this as "insert", which is also what makes a second save of the same aggregate
            // fail on the primary key rather than silently overwrite.
            null,
            requireCreationTimestamp(createdAt),
            requireCreationTimestamp(createdAt)
        );
    }

    /**
     * Rebuilds an intent from already-persisted state. Deliberately does NOT re-normalize: those
     * values passed through {@link #create} before they were stored, so recomputing on read would
     * mask corruption rather than repair it. Unlike create, it can restore any status.
     */
    public static PaymentIntent reconstitute(
        PaymentIntentId paymentIntentId,
        MerchantId merchantId,
        String orderId,
        String customerId,
        long amountMinor,
        String currency,
        CaptureMethod captureMethod,
        PaymentIntentStatus status,
        long capturedAmountMinor,
        long refundedAmountMinor,
        String failureCode,
        String failureMessage,
        String cancellationReason,
        Instant cancelledAt,
        String description,
        Map<String, String> metadata,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new PaymentIntent(
            paymentIntentId,
            merchantId,
            orderId,
            customerId,
            amountMinor,
            currency,
            captureMethod,
            status,
            capturedAmountMinor,
            refundedAmountMinor,
            failureCode,
            failureMessage,
            cancellationReason,
            cancelledAt,
            description,
            metadata == null ? Map.of() : Map.copyOf(metadata),
            version,
            createdAt,
            updatedAt
        );
    }

    /**
     * The only transition reachable in this PR.
     * <p>
     * Cancelling is also what releases the order's live-intent slot, so refusing it from a state
     * that has no other exit would strand the order permanently. The set of cancellable states is
     * therefore widened by every PR that adds a state a customer can sit in -- see
     * {@link #CANCELLABLE}.
     * <p>
     * The refusal is also what makes a repeated cancel a 409 rather than a silent overwrite of the
     * first cancellation's timestamp and reason.
     */
    public PaymentIntent cancel(String reason, Instant cancelledAt) {
        if (!CANCELLABLE.contains(status)) {
            throw new PaymentIntentNotCancellableException(paymentIntentId, status);
        }

        if (cancelledAt == null) {
            throw new IllegalArgumentException("Cancellation timestamp cannot be null");
        }

        return new PaymentIntent(
            paymentIntentId,
            merchantId,
            orderId,
            customerId,
            amountMinor,
            currency,
            captureMethod,
            PaymentIntentStatus.CANCELLED,
            capturedAmountMinor,
            refundedAmountMinor,
            failureCode,
            failureMessage,
            normalizeOptional(reason, CANCELLATION_REASON_MAX_LENGTH, "Cancellation reason"),
            cancelledAt,
            description,
            metadata,
            version,
            createdAt,
            cancelledAt
        );
    }

    private static PaymentIntentId requirePaymentIntentId(PaymentIntentId paymentIntentId) {
        if (paymentIntentId == null) {
            throw new IllegalArgumentException("Payment Intent Identifier cannot be null");
        }

        return paymentIntentId;
    }

    private static MerchantId requireMerchantId(MerchantId merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant Identifier cannot be null");
        }

        return merchantId;
    }

    /**
     * Required, unlike the customer link. An intent with no order has no obligation to collect
     * against, and the amount rule the create path applies has nothing to compare itself to.
     */
    private static String requireOrderId(String orderId) {
        String normalized = normalizeOptional(orderId, ORDER_REFERENCE_MAX_LENGTH, "Order identifier");

        if (normalized == null) {
            throw new IllegalArgumentException("Order identifier is required");
        }

        return normalized;
    }

    private static Instant requireCreationTimestamp(Instant createdAt) {
        if (createdAt == null) {
            throw new IllegalArgumentException("Creation timestamp cannot be null");
        }

        return createdAt;
    }

    private static long requireAmount(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be a positive number of minor units");
        }

        if (amountMinor > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException(
                "Amount cannot exceed " + MAX_AMOUNT_MINOR + " minor units"
            );
        }

        return amountMinor;
    }

    private static String requireCurrency(String currency) {
        if (currency == null) {
            throw new IllegalArgumentException("Currency cannot be null");
        }

        String normalized = currency.trim().toUpperCase(Locale.ROOT);

        if (normalized.length() != CURRENCY_LENGTH || !normalized.chars().allMatch(Character::isLetter)) {
            throw new IllegalArgumentException("Currency must be a three-letter ISO 4217 code");
        }

        return normalized;
    }

    /**
     * Capped so a merchant cannot use the intent table as free key-value storage: unbounded JSONB
     * would grow every row, every index scan and every response with data the platform never reads.
     */
    private static Map<String, String> requireMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }

        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException(
                "Metadata cannot hold more than " + MAX_METADATA_ENTRIES + " entries"
            );
        }

        metadata.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Metadata keys cannot be blank");
            }

            if (key.length() > MAX_METADATA_KEY_LENGTH) {
                throw new IllegalArgumentException(
                    "Metadata keys cannot be longer than " + MAX_METADATA_KEY_LENGTH + " characters"
                );
            }

            if (value != null && value.length() > MAX_METADATA_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                    "Metadata values cannot be longer than " + MAX_METADATA_VALUE_LENGTH
                        + " characters"
                );
            }
        });

        return Map.copyOf(metadata);
    }

    /**
     * Optional text fields: absent and whitespace-only mean the same thing and both become null, so
     * "  " and null cannot become two different rows under a constraint that treats only one of
     * them as absent.
     */
    private static String normalizeOptional(String value, int maxLength, String fieldName) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            return null;
        }

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                fieldName + " cannot be longer than " + maxLength + " characters"
            );
        }

        return normalized;
    }

    public PaymentIntentId paymentIntentId() {
        return paymentIntentId;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public String orderId() {
        return orderId;
    }

    public String customerId() {
        return customerId;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public CaptureMethod captureMethod() {
        return captureMethod;
    }

    public PaymentIntentStatus status() {
        return status;
    }

    public long capturedAmountMinor() {
        return capturedAmountMinor;
    }

    public long refundedAmountMinor() {
        return refundedAmountMinor;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public String description() {
        return description;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * The persisted row's optimistic-lock version, or null for an intent that has never been saved.
     * <p>
     * A persistence detail carried by the aggregate on purpose: an update has to prove it is writing
     * over the row it read, and the alternative -- re-reading inside the adapter -- would put a
     * read-modify-write between the two statements that concurrency control exists to avoid.
     */
    public Integer version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
