package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The payment capability's persistence port.
 * <p>
 * Every read takes a MerchantId. That is the tenant boundary expressed as a method signature: there
 * is deliberately no findByPaymentIntentId(PaymentIntentId), because a caller holding only an id --
 * guessed, leaked, or copied from another merchant's response -- must not be able to reach a row.
 */
public interface PaymentIntentRepository {

    /**
     * A convenience for a friendlier error, NOT the uniqueness guarantee. Two concurrent creates can
     * both pass this and {@code uq_payment_intents_live_per_order} is what stops both from landing,
     * so an implementation must still translate the violation rather than rely on callers checking
     * first.
     */
    boolean existsLiveForOrder(MerchantId merchantId, String orderId);

    PaymentIntent save(PaymentIntent paymentIntent);

    Optional<PaymentIntent> findByPaymentIntentId(MerchantId merchantId, PaymentIntentId paymentIntentId);

    /**
     * The same read, holding a row lock until the caller's transaction ends (SELECT ... FOR UPDATE).
     * <p>
     * EVERY TRANSITION USES THIS, and a plain read would be wrong for all of them. Two concurrent
     * writers that both read an intent and both decide from what they read end up racing on the
     * optimistic {@code version}, and the loser gets an "unexpected row count" failure -- a 500 for
     * a double-clicked confirm. Under the lock the second one waits, re-reads the winner's committed
     * state, and is refused by the state machine with an answer that names the real situation
     * ("cannot be confirmed while it is PROCESSING").
     * <p>
     * MUST be called inside a transaction. Reads that only read use {@link #findByPaymentIntentId}.
     */
    Optional<PaymentIntent> findByPaymentIntentIdForUpdate(
        MerchantId merchantId, PaymentIntentId paymentIntentId
    );

    /**
     * THE ONE READ IN THIS PORT THAT DOES NOT TAKE A MERCHANT, AND IT IS NAMED SO THAT NOBODY
     * REACHES FOR IT BY ACCIDENT.
     * <p>
     * A provider callback has no merchant to scope by. It arrives on a shared-secret endpoint with
     * no bearer token, names an intent, and the merchant is <b>derived</b> from the row it finds --
     * the same asymmetry that makes {@code pk_provider_callbacks} not merchant-leading. Requiring a
     * merchant here would mean taking one from the caller, and a caller-supplied tenant on an
     * endpoint that moves payments to SUCCEEDED is precisely the thing ADR-007 exists to prevent.
     * <p>
     * <b>Not for any other caller.</b> Every merchant-facing path uses
     * {@link #findByPaymentIntentIdForUpdate(MerchantId, PaymentIntentId)}, where the merchant
     * argument is the authorization and an id in a path proves nothing.
     * <p>
     * Locking, and MUST be called inside a transaction: the lock is what orders two <em>different</em>
     * callbacks for one intent, while the primary key orders two identical ones. Neither does the
     * other's job (ADR-012).
     */
    Optional<PaymentIntent> findForProviderCallbackForUpdate(PaymentIntentId paymentIntentId);

    /**
     * One page of the merchant's intents, newest first, starting strictly after {@code cursor} and
     * ordered by {@code (createdAt, paymentIntentId)} descending. The tiebreak is part of the
     * contract: an implementation that orders by timestamp alone will skip or repeat rows that
     * share one.
     *
     * @param status  filters by status when given; null means every status
     * @param orderId filters to one order's intents when given; null means every order
     */
    List<PaymentIntent> findPage(
        MerchantId merchantId,
        PaymentIntentStatus status,
        String orderId,
        PaymentIntentCursor cursor,
        int limit
    );

    /**
     * THE SECOND READ IN THIS PORT WITHOUT A MERCHANT, AND IT IS NAMED SO THAT NOBODY REACHES FOR IT
     * BY ACCIDENT.
     * <p>
     * The PROCESSING timeout runs on a timer. It has no caller, no token and therefore no tenant,
     * and the intents it must find are spread across every merchant -- so the caller-scoped reads
     * above cannot serve it, and having the scheduler supply a merchant would be exactly the
     * caller-supplied tenant ADR-007 exists to prevent. Same asymmetry as
     * {@link #findForProviderCallbackForUpdate(PaymentIntentId)}, for a related reason.
     * <p>
     * <b>Tenant-agnostic is not tenant-unsafe.</b> Each returned intent carries its own merchant and
     * every write that follows is scoped by it. Nothing is written here and nothing is decided from
     * it: it is a candidate list, re-checked under a row lock before anything moves.
     * <p>
     * "Sat in PROCESSING since" is read from {@code updated_at}, which the confirm -- or the
     * re-confirm after a 3DS challenge -- stamped. It is deliberately NOT
     * {@code payment_attempts.last_provider_event_at}: that column is ADR-012's monotonic ordering
     * guard and the timeout must neither read it nor write it.
     *
     * @param confirmedBefore intents whose {@code updatedAt} is at or before this instant are
     *                        returned. The caller computes it as {@code now - age}
     * @param limit           the batch size, so one sweep cannot load an unbounded backlog
     * @return PROCESSING intents older than the cutoff, longest-stranded first, across all merchants
     */
    List<String> findStrandedInProcessing(Instant confirmedBefore, int limit);

    /**
     * Intents abandoned before they were ever confirmed: still REQUIRES_PAYMENT_METHOD or
     * REQUIRES_CONFIRMATION, and untouched since {@code untouchedBefore}. Oldest first.
     * <p>
     * <b>These are the states a customer strands an intent in by simply closing the tab</b>, and they
     * are categorically different from PROCESSING. Nothing was ever sent to a provider, so no money
     * can be in flight, so cancelling one cannot erase a payment that really happened -- the exact
     * risk that makes PROCESSING uncancellable (ADR-015). Cancelling here is safe in a way that
     * timing out a PROCESSING intent is not, and the two must not be tuned as if they were one knob.
     * <p>
     * Unscoped by merchant, like the other sweeps: abandonment is not a tenant-specific event and the
     * merchant is read off each row rather than supplied.
     * <p>
     * <b>Identifiers, not aggregates.</b> The cancel this feeds re-reads the intent under a row lock
     * and uses nothing else off the candidate, so mapping one here was discarded work done outside
     * the sweep's per-item try/catch -- where one unrehydratable row kills the run. See
     * {@link AbandonedIntent}.
     */
    List<AbandonedIntent> findAbandonedBeforeConfirmation(Instant untouchedBefore, int limit);
}
