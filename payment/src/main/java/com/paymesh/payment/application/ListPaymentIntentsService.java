package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

public final class ListPaymentIntentsService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final PaymentIntentRepository paymentIntents;

    public ListPaymentIntentsService(PaymentIntentRepository paymentIntents) {
        this.paymentIntents = paymentIntents;
    }

    /**
     * One page of the caller's own intents, newest first.
     * <p>
     * A limit above {@link #MAX_LIMIT} is capped rather than rejected -- asking for too much is not
     * a mistake worth failing a read over. A limit below 1 IS rejected: it cannot be honoured, and
     * silently turning 0 into 20 would hand back a page the caller did not ask for.
     *
     * @param status  filters by status when given; null means every status
     * @param orderId filters to one order's intents when given; null means every order
     * @param cursor  the opaque position from a previous page, or null for the first
     */
    public PaymentIntentPage list(
        MerchantId merchantId,
        PaymentIntentStatus status,
        String orderId,
        String cursor,
        Integer limit
    ) {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant ID cannot be null");
        }

        int appliedLimit = applyLimit(limit);
        PaymentIntentCursor from = PaymentIntentCursor.decode(cursor);

        // One more row than the caller asked for. Its existence is the answer to "is there another
        // page?", which beats a second COUNT query that could disagree with the page it describes.
        List<PaymentIntent> found = paymentIntents.findPage(
            merchantId, status, normalize(orderId), from, appliedLimit + 1
        );

        boolean hasMore = found.size() > appliedLimit;
        List<PaymentIntent> page = hasMore ? found.subList(0, appliedLimit) : found;

        return new PaymentIntentPage(page, hasMore ? cursorAfter(page) : null, hasMore, appliedLimit);
    }

    /** A blank filter is no filter: "?orderId=" must not mean "orders whose id is the empty string". */
    private static String normalize(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }

        return orderId.trim();
    }

    private static int applyLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }

        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }

        return Math.min(limit, MAX_LIMIT);
    }

    private static String cursorAfter(List<PaymentIntent> page) {
        PaymentIntent last = page.get(page.size() - 1);

        return PaymentIntentCursor.of(last.createdAt(), last.paymentIntentId().value()).encode();
    }
}
