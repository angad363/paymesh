package com.paymesh.risk.infrastructure.payment;

import com.paymesh.risk.application.PaymentVelocityLookup;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * Risk's {@link PaymentVelocityLookup}, answered by counting Payment's rows.
 * <p>
 * Adapter in the consumer's infrastructure, same as everything else that crosses a module boundary
 * here. It reads {@code payment_intents} through its own Spring Data interface rather than through
 * a Payment service, which is the one place this pattern bends: there is no Payment use case that
 * means "count a customer's recent intents", and inventing one so this could call it would be a
 * service that exists only to be called by an adapter.
 * <p>
 * The cost is that Risk's infrastructure knows Payment's table name. That is a real coupling and it
 * is written down rather than hidden -- when Risk is extracted, this is the query that has to
 * become an API call, and it is the only one.
 */
public final class PaymentModuleVelocityLookup implements PaymentVelocityLookup {

    private final SpringDataPaymentIntentCounter intents;

    public PaymentModuleVelocityLookup(SpringDataPaymentIntentCounter intents) {
        this.intents = intents;
    }

    @Override
    public int intentsCreatedSince(MerchantId merchantId, String customerId, Instant since) {
        // int rather than long: this is a velocity count over a window measured in minutes, and a
        // merchant with more than two billion intents for one customer in that window has a
        // different problem than the one this feature exists to detect.
        return Math.toIntExact(
            intents.countByMerchantIdAndCustomerIdAndCreatedAtGreaterThanEqual(
                merchantId.value(), customerId, since
            )
        );
    }
}
