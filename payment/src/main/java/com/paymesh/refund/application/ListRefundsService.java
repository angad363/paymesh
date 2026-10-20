package com.paymesh.refund.application;

import com.paymesh.refund.domain.Refund;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

/**
 * A merchant's refunds, newest first.
 * <p>
 * Keyset pagination rather than an offset, for the reason every other list here uses it: an offset
 * skips and repeats rows when the underlying set changes between pages, and refunds are created
 * while somebody is paging through them.
 */
public final class ListRefundsService {

    /** The cap the API silently applies. Matches orders and payment intents. */
    public static final int MAX_LIMIT = 100;

    private static final int DEFAULT_LIMIT = 20;

    private final RefundRepository refunds;

    public ListRefundsService(RefundRepository refunds) {
        this.refunds = refunds;
    }

    public RefundPage list(MerchantId merchantId, String cursor, Integer limit) {
        int bounded = boundedLimit(limit);
        RefundCursor position = RefundCursor.decode(cursor);

        // One more than asked for, so the presence of a next page is known without a count query.
        // The extra row is dropped below and never reaches the caller.
        List<Refund> page = refunds.findPage(merchantId, position, bounded + 1);

        if (page.size() <= bounded) {
            return new RefundPage(page, null);
        }

        List<Refund> trimmed = List.copyOf(page.subList(0, bounded));
        Refund last = trimmed.get(trimmed.size() - 1);

        return new RefundPage(
            trimmed, new RefundCursor(last.createdAt(), last.refundId().value())
        );
    }

    /**
     * Over the cap is silently capped; at or below zero is a client error.
     * <p>
     * Not symmetric, and matching orders: asking for 5000 is a caller who wants "lots" and is
     * served 100, while asking for 0 or -1 is a caller whose arithmetic is wrong and who is better
     * told so.
     */
    private static int boundedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }

        if (limit < 1) {
            throw new IllegalArgumentException("Limit must be at least 1");
        }

        return Math.min(limit, MAX_LIMIT);
    }
}
