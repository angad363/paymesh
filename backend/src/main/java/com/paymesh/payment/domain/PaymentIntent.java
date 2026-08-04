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
     * The states a merchant may cancel from.
     * <p>
     * <b>Every state a customer can strand an intent in must end up in this set</b>, because
     * {@code uq_payment_intents_live_per_order} frees an order's slot only on FAILED or CANCELLED --
     * an uncancellable state is a dead order, which is worse than the overpayment the index
     * prevents.
     * <p>
     * REQUIRES_ACTION IS IN THE SET, AND IT IS NOT OPTIONAL (ADR-011, ADR-012). A customer who
     * abandons a 3DS challenge, closes the tab or lets it time out is ordinary behaviour, and
     * without this the intent sits in REQUIRES_ACTION forever, the merchant cannot create a second
     * one, cannot mark the order paid, and cannot re-create the order without colliding with their
     * own merchantOrderReference. It composes with the callback path: a challenge completed after
     * the cancel lands on a CANCELLED intent and is refused as IGNORED_TERMINAL.
     * <p>
     * AUTHORIZED IS IN THE SET, AND ADR-011'S SLOT TABLE REQUIRES IT. A MANUAL-capture intent stops
     * at AUTHORIZED and waits for the merchant, so it is a state an abandoned checkout sits in
     * indefinitely -- and one the merchant may legitimately decide against after the fact ("out of
     * stock after all"). Without this route the slot is held forever by funds nobody intends to
     * take. Cancelling here is also the honest counterpart of {@link #capture}: releasing an
     * authorization rather than collecting it.
     * <p>
     * PROCESSING is the deliberate exception and will never be added: an in-flight attempt may
     * already have succeeded at the provider, so cancelling locally could erase a payment that
     * really happened -- money taken from a customer with no record of it on this side, which is
     * strictly worse than a stuck order (ADR-011 section 5). The PROCESSING TIMEOUT is not a cancel
     * and does not appear here; it drives the intent to FAILED and its reasoning is ADR-015's.
     */
    private static final Set<PaymentIntentStatus> CANCELLABLE = Set.of(
        PaymentIntentStatus.REQUIRES_PAYMENT_METHOD,
        PaymentIntentStatus.REQUIRES_CONFIRMATION,
        PaymentIntentStatus.REQUIRES_ACTION,
        PaymentIntentStatus.AUTHORIZED
    );

    /**
     * The one state a provider callback may move an intent out of.
     * <p>
     * All four provider outcomes come from PROCESSING and nowhere else. In particular AUTHORIZED to
     * SUCCEEDED is <b>not</b> here: that is a capture, the merchant asks for it, and the PR that
     * owns manual capture owns the transition. A SUCCEEDED callback arriving against an AUTHORIZED
     * intent is therefore refused as IGNORED_TERMINAL rather than quietly capturing on the
     * provider's say-so.
     */
    private static final Set<PaymentIntentStatus> CONFIRMABLE = Set.of(
        PaymentIntentStatus.REQUIRES_CONFIRMATION,
        // The 3DS loop: the customer completed the challenge and the merchant confirms again,
        // opening a second attempt against the same intent. This is the cycle that makes the
        // monotonic event clock necessary -- inside it, a stale REQUIRES_ACTION is a LEGAL
        // transition and the state machine alone cannot refuse it (ADR-012).
        PaymentIntentStatus.REQUIRES_ACTION
    );

    /**
     * The failure code that means "the provider never answered", written by ADR-015's timeout sweep
     * and by nothing else.
     * <p>
     * IT LIVES IN THE DOMAIN BECAUSE THE AGGREGATE BRANCHES ON IT (ADR-026, see
     * {@code isUnansweredTimeout}). It began as a private constant in
     * {@code TimeOutProcessingPaymentsService}, which was right while it was only a label on a row;
     * once the state machine reads it, it is part of the aggregate's own vocabulary, and leaving it
     * in the application layer would either invert the dependency direction or duplicate the string
     * in two places that must never disagree.
     * <p>
     * Deliberately distinct from any code an issuer would send. {@code do_not_honour} or
     * {@code insufficient_funds} would claim the issuer answered; it did not, and the entire point
     * of the row is that PayMesh gave up waiting.
     */
    public static final String TIMEOUT_FAILURE_CODE = "provider_no_response";

    private static final int CUSTOMER_REFERENCE_MAX_LENGTH = 40;
    private static final int ORDER_REFERENCE_MAX_LENGTH = 40;
    private static final int DESCRIPTION_MAX_LENGTH = 500;
    private static final int CANCELLATION_REASON_MAX_LENGTH = 200;
    private static final int FAILURE_CODE_MAX_LENGTH = 60;
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 500;
    private static final int CURRENCY_LENGTH = 3;

    private final PaymentIntentId paymentIntentId;
    private final MerchantId merchantId;
    private final String orderId;
    private final String customerId;
    private final long amountMinor;
    private final String currency;
    private final CaptureMethod captureMethod;
    private final PaymentMethodType paymentMethodType;
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
        PaymentMethodType paymentMethodType,
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
        this.paymentMethodType = paymentMethodType;
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
            // No method until one is attached, which is also what
            // ck_payment_intents_method_known tolerates in exactly this state.
            null,
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
        PaymentMethodType paymentMethodType,
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
            paymentMethodType,
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
     * REQUIRES_PAYMENT_METHOD to REQUIRES_CONFIRMATION.
     * <p>
     * Attaching records the KIND of instrument, never the instrument (see {@link PaymentMethodType}).
     * It moves no money and calls nothing.
     * <p>
     * Legal from exactly one state. Re-attaching to change a chosen method is not supported and is
     * refused rather than quietly allowed: past this point the intent may already have an attempt
     * in flight, and a method that disagrees with what the provider was actually asked for is worse
     * than no record at all. A merchant who chose wrongly cancels and starts again -- which is
     * available precisely because REQUIRES_CONFIRMATION is cancellable.
     */
    public PaymentIntent attach(PaymentMethodType paymentMethodType, Instant attachedAt) {
        if (status != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD) {
            throw new PaymentMethodNotAttachableException(paymentIntentId, status);
        }

        if (paymentMethodType == null) {
            throw new IllegalArgumentException("Payment method type cannot be null");
        }

        if (attachedAt == null) {
            throw new IllegalArgumentException("Attach timestamp cannot be null");
        }

        return withStatus(
            paymentMethodType, PaymentIntentStatus.REQUIRES_CONFIRMATION, attachedAt
        );
    }

    /**
     * REQUIRES_CONFIRMATION to PROCESSING.
     * <p>
     * This is the money-moving transition -- the one after which PayMesh believes a collection is
     * under way -- even though nothing is called: the outcome is genuinely undecided until a
     * provider callback resolves it, which is why the endpoint answers 202 and why PROCESSING has
     * no local cancel.
     * <p>
     * Confirming from REQUIRES_PAYMENT_METHOD is refused, not implied. An intent with no method
     * attached has nothing to be collected with, and inferring a default here would pick an
     * instrument on the merchant's behalf.
     * <p>
     * REQUIRES_ACTION is also confirmable -- see {@link #CONFIRMABLE}. That is the 3DS loop, and it
     * opens a second attempt rather than reusing the first: what the provider was asked, and when,
     * is a per-try fact.
     */
    public PaymentIntent confirm(Instant confirmedAt) {
        if (!CONFIRMABLE.contains(status)) {
            throw new PaymentIntentNotConfirmableException(paymentIntentId, status);
        }

        if (confirmedAt == null) {
            throw new IllegalArgumentException("Confirmation timestamp cannot be null");
        }

        return withStatus(paymentMethodType, PaymentIntentStatus.PROCESSING, confirmedAt);
    }

    /**
     * PROCESSING to AUTHORIZED: the provider is holding the funds and has not moved them. Only a
     * MANUAL-capture intent should legitimately land here, but which it is is the provider's report
     * and not this aggregate's to second-guess -- an AUTHORIZED answer to an AUTOMATIC intent is a
     * divergence worth recording, not one worth rewriting.
     * <p>
     * No captured amount, deliberately: an authorization has captured nothing, and writing the
     * authorized figure into {@code captured_amount_minor} would make an uncaptured payment look
     * collected to every reader of that column.
     */
    public PaymentIntent authorize(Instant authorizedAt) {
        requireProviderTransition(PaymentIntentStatus.AUTHORIZED, authorizedAt);

        return withStatus(paymentMethodType, PaymentIntentStatus.AUTHORIZED, authorizedAt);
    }

    /**
     * PROCESSING to REQUIRES_ACTION: the customer has to do something off-site, typically a 3DS
     * challenge. The intent is parked, not finished, and it has two ways out -- the merchant
     * confirms again once the challenge completes, or the merchant cancels (see {@link #CANCELLABLE}).
     */
    public PaymentIntent requireAction(Instant requiredAt) {
        requireProviderTransition(PaymentIntentStatus.REQUIRES_ACTION, requiredAt);

        return withStatus(paymentMethodType, PaymentIntentStatus.REQUIRES_ACTION, requiredAt);
    }

    /**
     * PROCESSING to SUCCEEDED.
     * <p>
     * <b>Reaching SUCCEEDED moves no balance and posts no ledger entry</b> (design section 0.5).
     * This is operational state: it says a provider reported a collection, and the Ledger -- which
     * does not exist -- remains the financial source of truth. The captured amount is written here
     * because {@code ck_payment_intents_succeeded_captured} refuses a succeeded intent that captured
     * nothing, and because a payment record that cannot say how much it took is not a record.
     * <p>
     * The amount is checked against the intent's own. A provider does not get to change what is
     * owed, and the caller has already refused a mismatch before reaching this -- this is the
     * backstop, not the check.
     */
    public PaymentIntent succeed(long capturedAmountMinor, Instant succeededAt) {
        requireProviderTransition(PaymentIntentStatus.SUCCEEDED, succeededAt);

        if (capturedAmountMinor <= 0 || capturedAmountMinor > amountMinor) {
            throw new IllegalArgumentException(
                "Captured amount must be between 1 and the intent's " + amountMinor + " minor units"
            );
        }

        return new PaymentIntent(
            paymentIntentId,
            merchantId,
            orderId,
            customerId,
            amountMinor,
            currency,
            captureMethod,
            paymentMethodType,
            PaymentIntentStatus.SUCCEEDED,
            capturedAmountMinor,
            refundedAmountMinor,
            failureCode,
            failureMessage,
            cancellationReason,
            cancelledAt,
            description,
            metadata,
            version,
            createdAt,
            succeededAt
        );
    }

    /**
     * A refund of this payment succeeded: raise the refunded total and move the status with it.
     *
     * <h2>THIS IS WHAT FINALLY MAKES PARTIALLY_REFUNDED AND REFUNDED REACHABLE</h2>
     *
     * Both have been declared in {@link PaymentIntentStatus} since V8 and unreachable ever since --
     * {@code project-status.md} verified it by grep. The enum's own comment said "the Refund
     * capability; not this design". This is that capability arriving.
     *
     * <h2>Payment moves its own column, because Refund may not</h2>
     *
     * Called by Payment's consumer of {@code refund.succeeded}, exactly as {@code Order.markPaid}
     * is called by Order's consumer of {@code payment.succeeded} (ADR-016). Refund never writes
     * this table any more than Payment writes the orders table, so the arrow between them stays
     * one-way and nothing imports Refund.
     *
     * <h2>SUCCEEDED is a legal starting point and so is PARTIALLY_REFUNDED</h2>
     *
     * A payment refunded in parts passes through PARTIALLY_REFUNDED repeatedly before reaching
     * REFUNDED. Anything else -- FAILED, CANCELLED, an already fully REFUNDED payment -- means a
     * refund succeeded against money this intent does not think it holds, which is a
     * reconciliation problem rather than a state transition, and the consumer logs it rather than
     * forcing it through.
     */
    public PaymentIntent applyRefund(long refundAmountMinor, Instant refundedAt) {
        if (status != PaymentIntentStatus.SUCCEEDED
            && status != PaymentIntentStatus.PARTIALLY_REFUNDED) {
            throw new PaymentIntentNotRefundableException(paymentIntentId, status);
        }

        if (refundAmountMinor <= 0) {
            throw new IllegalArgumentException(
                "A refund amount must be positive, got " + refundAmountMinor
            );
        }

        long refundedTotal = refundedAmountMinor + refundAmountMinor;

        // THE COMPARISON IS AGAINST WHAT WAS CAPTURED, never against amountMinor. On a partial
        // capture the two differ by money that was never collected, and allowing refunds up to the
        // intent's amount would let more go out than ever came in. Refund's own deferred trigger
        // says the same thing at the schema; this is the second reader of that rule, and if they
        // ever disagree the database wins.
        if (refundedTotal > capturedAmountMinor) {
            throw new IllegalArgumentException(
                "Refunds of " + refundedTotal + " would exceed the " + capturedAmountMinor
                    + " captured by payment intent " + paymentIntentId.value()
            );
        }

        return new PaymentIntent(
            paymentIntentId,
            merchantId,
            orderId,
            customerId,
            amountMinor,
            currency,
            captureMethod,
            paymentMethodType,
            refundedTotal == capturedAmountMinor
                ? PaymentIntentStatus.REFUNDED
                : PaymentIntentStatus.PARTIALLY_REFUNDED,
            capturedAmountMinor,
            refundedTotal,
            failureCode,
            failureMessage,
            cancellationReason,
            cancelledAt,
            description,
            metadata,
            version,
            createdAt,
            refundedAt
        );
    }

    /**
     * PROCESSING to FAILED, with the provider's reason after redaction.
     * <p>
     * FAILED also releases the order's live-intent slot, which is why a failed collection does not
     * kill the order: the merchant can create a fresh intent and try again.
     */
    public PaymentIntent fail(String failureCode, String failureMessage, Instant failedAt) {
        requireProviderTransition(PaymentIntentStatus.FAILED, failedAt);

        return new PaymentIntent(
            paymentIntentId,
            merchantId,
            orderId,
            customerId,
            amountMinor,
            currency,
            captureMethod,
            paymentMethodType,
            PaymentIntentStatus.FAILED,
            capturedAmountMinor,
            refundedAmountMinor,
            normalizeOptional(failureCode, FAILURE_CODE_MAX_LENGTH, "Failure code"),
            normalizeOptional(failureMessage, FAILURE_MESSAGE_MAX_LENGTH, "Failure message"),
            cancellationReason,
            cancelledAt,
            description,
            metadata,
            version,
            createdAt,
            failedAt
        );
    }

    /**
     * AUTHORIZED to SUCCEEDED: the merchant collects funds the provider is already holding.
     * <p>
     * THE ONE TRANSITION IN THIS AGGREGATE THAT REACHES SUCCEEDED WITHOUT A PROVIDER SAYING SO, and
     * it is why {@link #succeed} is not reused. A provider reporting SUCCEEDED against an AUTHORIZED
     * intent is refused as out of order (see {@link #requireProviderTransition}); a MERCHANT asking
     * for it is the entire point of MANUAL capture. Same destination, different authority, so
     * different method.
     * <p>
     * <b>Only legal when {@link CaptureMethod#MANUAL}.</b> An AUTOMATIC intent is captured by the
     * provider callback that reports SUCCEEDED, and letting a merchant capture one by hand would mean
     * two paths racing to collect the same authorization.
     * <p>
     * PARTIAL CAPTURE IS ALLOWED AND FULL IS THE DEFAULT. Capturing less than the authorized amount
     * leaves {@code captured_amount_minor < amount_minor} with the status still SUCCEEDED, which is
     * what will eventually make {@code orders.PARTIALLY_PAID} reachable -- through the
     * {@code payment.succeeded} consumer that does not exist yet, never through a second intent.
     * Capturing MORE is refused here and refused again by
     * {@code ck_payment_intents_captured}; overcapture is not a policy to revisit, it is a number
     * that must not exist. Zero is refused too, by
     * {@code ck_payment_intents_succeeded_captured} and by this method: a succeeded payment that
     * collected nothing is a contradiction, and a merchant who wants to collect nothing cancels.
     * <p>
     * <b>A KNOWN FUTURE CHANGE, stated so nobody treats it as settled</b> (design spec section 5).
     * With a real provider this becomes AUTHORIZED to PROCESSING plus a capture callback. The
     * synchronous path is correct for a platform with no provider and will be REPLACED, not extended.
     *
     * @param requestedAmountMinor how much to take, in minor units. Never null at this layer -- the
     *                             service resolves an absent request to the full authorized amount,
     *                             because "capture" with no figure means "all of it"
     */
    public PaymentIntent capture(long requestedAmountMinor, Instant capturedAt) {
        if (status != PaymentIntentStatus.AUTHORIZED) {
            throw new PaymentIntentNotCapturableException(paymentIntentId, status);
        }

        if (captureMethod != CaptureMethod.MANUAL) {
            throw new PaymentIntentNotCapturableException(paymentIntentId, captureMethod);
        }

        if (capturedAt == null) {
            throw new IllegalArgumentException("Capture timestamp cannot be null");
        }

        if (requestedAmountMinor <= 0) {
            throw new IllegalArgumentException("Capture amount must be a positive number of minor units");
        }

        // The database says this too, and the database is the guarantee. This check exists so the
        // merchant gets a 422 that names the two figures instead of a 500 carrying a constraint name.
        if (requestedAmountMinor > amountMinor) {
            throw new CaptureAmountExceedsAuthorizedException(
                paymentIntentId, requestedAmountMinor, amountMinor
            );
        }

        return new PaymentIntent(
            paymentIntentId,
            merchantId,
            orderId,
            customerId,
            amountMinor,
            currency,
            captureMethod,
            paymentMethodType,
            PaymentIntentStatus.SUCCEEDED,
            requestedAmountMinor,
            refundedAmountMinor,
            failureCode,
            failureMessage,
            cancellationReason,
            cancelledAt,
            description,
            metadata,
            version,
            createdAt,
            capturedAt
        );
    }

    /**
     * The shared guard for the four transitions only a provider can request.
     * <p>
     * <b>Terminal states absorb, and that is one of the two out-of-order mechanisms</b> (ADR-012). A
     * PROCESSING-era callback arriving after the intent already SUCCEEDED, FAILED or was CANCELLED
     * is refused here, and the callback path answers 200 IGNORED_TERMINAL rather than 409 -- a
     * provider retries on any non-2xx, so a conflict would be an infinite retry loop against a
     * payment that is already finished.
     * <p>
     * <b>With exactly one exception, added by ADR-026: a payment this platform failed because nobody
     * answered.</b> That state records the absence of an outcome rather than an outcome, so the
     * provider's word still settles it -- see {@link #isUnansweredTimeout()}, which is deliberately
     * narrow and is the only way past the check below.
     * <p>
     * It is only ONE of the two. The state machine cannot refuse a stale event inside the
     * PROCESSING/REQUIRES_ACTION cycle, because there the stale event is a legal transition. The
     * monotonic event clock is what refuses that, and neither mechanism subsumes the other.
     */
    private void requireProviderTransition(PaymentIntentStatus target, Instant occurredAt) {
        if (status != PaymentIntentStatus.PROCESSING && !isUnansweredTimeout()) {
            throw new ProviderOutcomeNotApplicableException(paymentIntentId, status, target);
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("Provider event timestamp cannot be null");
        }
    }

    /**
     * FAILED BECAUSE PayMesh GAVE UP WAITING -- WHICH IS A GUESS, NOT A FACT (ADR-026).
     *
     * <h2>The one terminal state that does not absorb, and why it is not a hole in the rule above</h2>
     *
     * Every other terminal state records something that HAPPENED and that PayMesh was told about: the
     * provider declined, the provider collected, the merchant cancelled. A late event contradicting
     * any of those is the provider being wrong or slow, and absorbing it is correct.
     * <p>
     * This one records the opposite. {@code TimeOutProcessingPaymentsService} writes it when the
     * provider said <b>nothing at all</b>, and its own javadoc calls it a guess in the safe
     * direction whose real answer is reconciliation; ADR-015 names that job as the mitigation and
     * the failure MESSAGE on the row says outright that the attempt "may still have succeeded at the
     * provider and needs reconciliation". Absorbing the answer when it finally arrives would make
     * that sentence unactionable: PayMesh would hold a FAILED payment, the provider would hold the
     * customer's money, and the two could never be reconciled because the reconciling event is
     * refused by the state machine.
     * <p>
     * So the guess is revisable, by the only thing that can settle it -- the provider's own word,
     * arriving late as a callback or read out of its daily file. <b>{@link #TIMEOUT_FAILURE_CODE} is
     * what makes this narrow rather than a general un-failing of payments</b>: it is written by
     * exactly one caller, it means "nobody answered", and a payment the provider actually declined
     * carries the provider's code instead and stays terminal forever.
     * <p>
     * The monotonic clock still applies on top of this. A revision must still be strictly newer than
     * the last provider event seen, so this widens WHICH states a provider may speak into -- not
     * whether a stale event may speak at all.
     */
    private boolean isUnansweredTimeout() {
        return status == PaymentIntentStatus.FAILED && TIMEOUT_FAILURE_CODE.equals(failureCode);
    }

    /**
     * The forward transitions that change nothing but the status, the method and the clock. Kept in
     * one place so a new transition cannot accidentally drop a field on the way through -- the
     * failure mode of copying a nineteen-argument constructor call.
     */
    private PaymentIntent withStatus(
        PaymentMethodType methodType,
        PaymentIntentStatus newStatus,
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
            methodType,
            newStatus,
            capturedAmountMinor,
            refundedAmountMinor,
            failureCode,
            failureMessage,
            cancellationReason,
            cancelledAt,
            description,
            metadata,
            version,
            createdAt,
            updatedAt
        );
    }

    /**
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
            paymentMethodType,
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

    /** Null until a method is attached, and null forever on an intent cancelled before that. */
    public PaymentMethodType paymentMethodType() {
        return paymentMethodType;
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
