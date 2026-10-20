package com.paymesh.order.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * A merchant's commercial intent: what is being bought, and how much is due.
 * <p>
 * An order does not talk to providers, does not post ledger entries and does not own payment
 * attempts. It states the obligation; Payment will state whether it was met.
 * <p>
 * The amount is a positive integer in MINOR UNITS with the currency held separately -- never a
 * decimal and never a float, because binary floating point cannot represent 0.10 and money that
 * cannot be represented cannot be audited.
 * <p>
 * Instances are immutable. {@link #cancel} returns a new Order rather than mutating this one,
 * matching the aggregate style used across PayMesh, and there is deliberately no status setter:
 * callers request a transition and the state machine decides whether it is legal.
 */
public final class Order {

    /**
     * Ten billion major units. Nothing this platform models is a legitimate charge above it, while
     * a value that large is a plausible units mistake (major units sent as minor) or a runaway
     * loop -- and BIGINT would store either without complaint.
     */
    public static final long MAX_AMOUNT_MINOR = 999_999_999_999L;

    public static final int MAX_METADATA_ENTRIES = 16;
    public static final int MAX_METADATA_KEY_LENGTH = 40;
    public static final int MAX_METADATA_VALUE_LENGTH = 500;

    private static final int CUSTOMER_REFERENCE_MAX_LENGTH = 40;
    private static final int MERCHANT_ORDER_REFERENCE_MAX_LENGTH = 100;
    private static final int DESCRIPTION_MAX_LENGTH = 500;
    private static final int CANCELLATION_REASON_MAX_LENGTH = 200;
    private static final int CURRENCY_LENGTH = 3;

    private final OrderId orderId;
    private final MerchantId merchantId;
    private final String customerId;
    private final String merchantOrderReference;
    private final long amountMinor;
    private final String currency;
    private final long amountPaidMinor;
    private final OrderStatus status;
    private final String description;
    private final Map<String, String> metadata;
    private final Instant expiresAt;
    private final String cancellationReason;
    private final Instant cancelledAt;
    private final Integer version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Order(
        OrderId orderId,
        MerchantId merchantId,
        String customerId,
        String merchantOrderReference,
        long amountMinor,
        String currency,
        long amountPaidMinor,
        OrderStatus status,
        String description,
        Map<String, String> metadata,
        Instant expiresAt,
        String cancellationReason,
        Instant cancelledAt,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.customerId = customerId;
        this.merchantOrderReference = merchantOrderReference;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.amountPaidMinor = amountPaidMinor;
        this.status = status;
        this.description = description;
        this.metadata = metadata;
        this.expiresAt = expiresAt;
        this.cancellationReason = cancellationReason;
        this.cancelledAt = cancelledAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Order create(
        OrderId orderId,
        MerchantId merchantId,
        String customerId,
        String merchantOrderReference,
        long amountMinor,
        String currency,
        String description,
        Map<String, String> metadata,
        Instant expiresAt,
        Instant createdAt
    ) {
        Instant validatedCreatedAt = requireCreationTimestamp(createdAt);

        return new Order(
            requireOrderId(orderId),
            requireMerchantId(merchantId),
            normalizeOptional(customerId, CUSTOMER_REFERENCE_MAX_LENGTH, "Customer identifier"),
            normalizeOptional(
                merchantOrderReference,
                MERCHANT_ORDER_REFERENCE_MAX_LENGTH,
                "Merchant order reference"
            ),
            requireAmount(amountMinor),
            requireCurrency(currency),
            0,
            OrderStatus.PENDING,
            normalizeOptional(description, DESCRIPTION_MAX_LENGTH, "Description"),
            requireMetadata(metadata),
            requireExpiryAfter(expiresAt, validatedCreatedAt),
            null,
            null,
            // Never persisted yet, so there is no row version to preserve. The persistence adapter
            // reads this as "insert", which is also what makes a second save of the same aggregate
            // fail on the primary key rather than silently overwrite.
            null,
            validatedCreatedAt,
            validatedCreatedAt
        );
    }

    /**
     * Rebuilds an Order from already-persisted state. Deliberately does NOT re-normalize: those
     * values passed through {@link #create} before they were stored, so recomputing on read would
     * mask corruption rather than repair it. Unlike create, it can restore any status.
     */
    public static Order reconstitute(
        OrderId orderId,
        MerchantId merchantId,
        String customerId,
        String merchantOrderReference,
        long amountMinor,
        String currency,
        long amountPaidMinor,
        OrderStatus status,
        String description,
        Map<String, String> metadata,
        Instant expiresAt,
        String cancellationReason,
        Instant cancelledAt,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new Order(
            orderId,
            merchantId,
            customerId,
            merchantOrderReference,
            amountMinor,
            currency,
            amountPaidMinor,
            status,
            description,
            metadata == null ? Map.of() : Map.copyOf(metadata),
            expiresAt,
            cancellationReason,
            cancelledAt,
            version,
            createdAt,
            updatedAt
        );
    }

    /**
     * PENDING to CANCELLED, at the merchant's request.
     * <p>
     * Cancelling a PAID order would erase an obligation that money has already settled against, so
     * the aggregate refuses from every state but PENDING. That refusal is also what makes a
     * repeated cancel a 409 rather than a silent overwrite of the first cancellation's timestamp.
     */
    public Order cancel(String reason, Instant cancelledAt) {
        if (status != OrderStatus.PENDING) {
            throw new OrderNotCancellableException(orderId, status);
        }

        if (cancelledAt == null) {
            throw new IllegalArgumentException("Cancellation timestamp cannot be null");
        }

        return new Order(
            orderId,
            merchantId,
            customerId,
            merchantOrderReference,
            amountMinor,
            currency,
            amountPaidMinor,
            OrderStatus.CANCELLED,
            description,
            metadata,
            expiresAt,
            normalizeOptional(reason, CANCELLATION_REASON_MAX_LENGTH, "Cancellation reason"),
            cancelledAt,
            version,
            createdAt,
            cancelledAt
        );
    }

    /**
     * PENDING to EXPIRED, because the merchant's own {@code expiresAt} has passed.
     * <p>
     * NO REASON AND NO TIMESTAMP COLUMN OF ITS OWN, unlike cancel. {@code expires_at} already says
     * why and when this was allowed to happen, and {@code ck_orders_cancellation} refuses a
     * {@code cancelled_at} on any status but CANCELLED -- an expiry is not a cancellation and must
     * not borrow its columns. The timeline row in {@code order_state_history} carries the rest.
     * <p>
     * <b>The aggregate refuses an order whose expiry has not arrived, and that guard is not
     * redundant with the sweeper's.</b> The sweeper selects candidates by {@code expires_at} and
     * re-checks under a row lock, but it is the only caller today and a second one would otherwise
     * be able to expire a live order by simply not checking. Expiry is a state a merchant cannot
     * undo, so the rule belongs where every caller must pass through it.
     *
     * @param expiredAt the sweep instant. Must be at or after {@code expiresAt}.
     */
    public Order expire(Instant expiredAt) {
        if (status != OrderStatus.PENDING) {
            throw new OrderNotExpirableException(orderId, status);
        }

        if (expiredAt == null) {
            throw new IllegalArgumentException("Expiry timestamp cannot be null");
        }

        // An order with no expiry never expires. Nothing to compare against, and treating "no
        // deadline" as "already past" would silently kill every open-ended order on the platform
        // the first time the sweeper ran.
        if (expiresAt == null || expiresAt.isAfter(expiredAt)) {
            throw new OrderNotExpirableException(orderId, status);
        }

        return new Order(
            orderId,
            merchantId,
            customerId,
            merchantOrderReference,
            amountMinor,
            currency,
            amountPaidMinor,
            OrderStatus.EXPIRED,
            description,
            metadata,
            expiresAt,
            cancellationReason,
            cancelledAt,
            version,
            createdAt,
            expiredAt
        );
    }

    /**
     * PENDING to PAID or PARTIALLY_PAID, because a payment against this order succeeded.
     * <p>
     * <b>THIS IS THE TRANSITION V5 DECLARED AND NOTHING COULD REACH.</b> {@code PAID} and
     * {@code PARTIALLY_PAID} have sat in the enum and in {@code ck_orders_status} since that
     * migration, waiting for a consumer of {@code payment.succeeded} to exist. It does now
     * (ADR-016), and this is the only method that reaches either.
     * <p>
     * <b>The split is decided against THIS ORDER'S {@code amountMinor}, never the payment's.</b> The
     * order states the obligation; a payment intent states an attempt to meet it, and while the two
     * figures agree today (an intent collects exactly its order's amount), comparing against the
     * payment's own number would mean an order was marked fully paid on the strength of a document
     * that is not the obligation. Equal is PAID; anything less is PARTIALLY_PAID.
     * <p>
     * PENDING only. A CANCELLED or EXPIRED order must not quietly become PAID -- a redelivered or
     * reconstructed event arriving after the merchant cancelled would otherwise resurrect it -- and a
     * PAID order must not be paid again. <b>This refusal is not redundant with the inbox</b>: the
     * inbox stops the SAME event being applied twice, and this stops a DIFFERENT event describing
     * the same collection from double-applying. Neither subsumes the other.
     * <p>
     * The amount bounds are checked here so the failure names the two figures, but
     * {@code ck_orders_amount_paid} is the guarantee -- it refuses {@code amount_paid_minor} above
     * {@code amount_minor} whatever wrote it.
     *
     * @param capturedAmountMinor how much was actually collected, in minor units. Positive, and at
     *                            most this order's own amount
     * @param paidAt              when the collecting authority says it happened
     */
    public Order markPaid(long capturedAmountMinor, Instant paidAt) {
        if (status != OrderStatus.PENDING) {
            throw new OrderPaymentNotApplicableException(orderId, status);
        }

        if (paidAt == null) {
            throw new IllegalArgumentException("Payment timestamp cannot be null");
        }

        if (capturedAmountMinor <= 0) {
            throw new IllegalArgumentException(
                "Paid amount must be a positive number of minor units"
            );
        }

        if (capturedAmountMinor > amountMinor) {
            throw new IllegalArgumentException(
                "Paid amount " + capturedAmountMinor + " exceeds the order's " + amountMinor
                    + " minor units"
            );
        }

        return new Order(
            orderId,
            merchantId,
            customerId,
            merchantOrderReference,
            amountMinor,
            currency,
            capturedAmountMinor,
            capturedAmountMinor == amountMinor ? OrderStatus.PAID : OrderStatus.PARTIALLY_PAID,
            description,
            metadata,
            expiresAt,
            cancellationReason,
            // Left null, as it must be: ck_orders_cancellation refuses a cancelled_at on any status
            // but CANCELLED, and a paid order was never cancelled.
            cancelledAt,
            version,
            createdAt,
            paidAt
        );
    }

    /**
     * Whether {@code expiredAt} has reached this order's deadline and it is still in a state that
     * could act on it.
     * <p>
     * A QUERY, NOT THE GUARD. It exists so the sweeper can skip an ineligible candidate quietly
     * instead of catching {@link OrderNotExpirableException} as control flow; {@link #expire} still
     * enforces the same rule for every caller. The two conditions are stated once here and repeated
     * there deliberately -- a predicate the aggregate does not itself honour is a comment, not a
     * rule.
     */
    public boolean hasExpiredBy(Instant expiredAt) {
        return status == OrderStatus.PENDING
            && expiresAt != null
            && !expiresAt.isAfter(expiredAt);
    }

    private static OrderId requireOrderId(OrderId orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("Order Identifier cannot be null");
        }

        return orderId;
    }

    private static MerchantId requireMerchantId(MerchantId merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant Identifier cannot be null");
        }

        return merchantId;
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
     * Capped so a merchant cannot use the order table as free key-value storage: unbounded JSONB
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

    private static Instant requireExpiryAfter(Instant expiresAt, Instant createdAt) {
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Expiry must be after the order is created");
        }

        return expiresAt;
    }

    /**
     * Optional text fields: absent and whitespace-only mean the same thing and both become null,
     * so "  " and null cannot become two different rows under a unique constraint that treats only
     * one of them as absent.
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

    public OrderId orderId() {
        return orderId;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public String customerId() {
        return customerId;
    }

    public String merchantOrderReference() {
        return merchantOrderReference;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public long amountPaidMinor() {
        return amountPaidMinor;
    }

    public OrderStatus status() {
        return status;
    }

    public String description() {
        return description;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    /**
     * The persisted row's optimistic-lock version, or null for an order that has never been saved.
     * <p>
     * A persistence detail carried by the aggregate on purpose: an update has to prove it is
     * writing over the row it read, and the alternative -- re-reading inside the adapter -- would
     * put a read-modify-write between the two statements that concurrency control exists to avoid.
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
