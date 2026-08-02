package com.paymesh.refund.application;

/**
 * No such refund, or it belongs to another merchant.
 * <p>
 * ONE exception for both, and the handler maps it to 404. Distinguishing them would answer 403 for
 * a refund that exists but is not yours -- which confirms it exists, and lets anyone enumerate
 * every refund on the platform by watching which ids answer 403 and which 404.
 */
public final class RefundNotFoundException extends RuntimeException {

    public RefundNotFoundException(String refundId) {
        super("No refund " + refundId);
    }
}
