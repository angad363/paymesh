package com.paymesh.payment.domain;

/**
 * Raised when a capture asks for more than the provider is holding.
 * <p>
 * <b>THIS IS NOT THE GUARANTEE.</b> {@code ck_payment_intents_captured} is -- it refuses
 * {@code captured_amount_minor > amount_minor} from any path, including a psql prompt, with no Java
 * in the way. Overcapture is not a business decision to be reviewed later; it is a number that must
 * not exist, and a rule enforced only in application code is not enforced.
 * <p>
 * What this exception buys is the ANSWER. Without it the request still fails, but it fails as a
 * constraint violation at flush time and the merchant is handed a 500 naming a PostgreSQL index. The
 * check is here so they get a 422 naming the two amounts instead.
 * <p>
 * It lives in the domain because the aggregate is what refuses. It carries no HTTP status -- the API
 * layer decides that.
 */
public class CaptureAmountExceedsAuthorizedException extends RuntimeException {

    public CaptureAmountExceedsAuthorizedException(
        PaymentIntentId paymentIntentId,
        long requestedAmountMinor,
        long authorizedAmountMinor
    ) {
        super("Payment intent " + paymentIntentId.value() + " authorized "
            + authorizedAmountMinor + " minor units and cannot capture " + requestedAmountMinor);
    }
}
