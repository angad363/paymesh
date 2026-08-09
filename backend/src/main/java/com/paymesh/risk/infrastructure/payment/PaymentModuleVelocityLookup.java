package com.paymesh.risk.infrastructure.payment;

import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.risk.application.PaymentVelocityLookup;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * Risk's {@link PaymentVelocityLookup}, answered by Payment.
 * <p>
 * Adapter in the consumer's infrastructure, calling the owning module's APPLICATION SERVICE -- the
 * same shape as {@code OrderModuleLookup} and {@code PaymentModuleLookup} (ADR-008).
 * <p>
 * <b>The first draft of this class declared its own {@code JpaRepository} over Payment's entity</b>,
 * which is faster to write and is precisely the shortcut {@code ModuleBoundaryTest}'s javadoc names
 * as the thing it refuses -- it just could not refuse it, because {@code risk} was missing from that
 * test's capability list. Both are fixed: the count is Payment's to answer, and the boundary test
 * now covers this package.
 */
public final class PaymentModuleVelocityLookup implements PaymentVelocityLookup {

    private final GetPaymentIntentService paymentIntents;

    public PaymentModuleVelocityLookup(GetPaymentIntentService paymentIntents) {
        this.paymentIntents = paymentIntents;
    }

    @Override
    public int intentsCreatedSince(
        MerchantId merchantId, String customerId, Instant since, String excludingIntentId
    ) {
        // int rather than long: this counts a window measured in minutes, and a merchant with more
        // than two billion intents for one customer in that window has a different problem than the
        // one this feature exists to detect.
        return Math.toIntExact(paymentIntents.countForCustomerSince(
            merchantId, customerId, since, PaymentIntentId.from(excludingIntentId)
        ));
    }
}
