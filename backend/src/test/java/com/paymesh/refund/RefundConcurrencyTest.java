package com.paymesh.refund;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
import com.paymesh.refund.application.CreateRefundCommand;
import com.paymesh.refund.application.CreateRefundService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE RACE THE WHOLE MODULE IS DESIGNED AROUND, run for real against PostgreSQL.
 *
 * <h2>WHY THIS TEST HAD TO EXIST</h2>
 *
 * Every other over-refund test is sequential, and a sequential test cannot distinguish the
 * application's pre-check from the database's trigger -- the pre-check catches everything on its
 * own when requests arrive one at a time. The concurrent case is the ONLY one where the pre-check
 * is structurally incapable of being right: both requests read a total that excludes the other,
 * both pass, and both are perfectly valid in isolation.
 * <p>
 * ADR-019 claims the deferred trigger settles it. This is that claim executed rather than argued.
 * Two threads, one barrier, two full refunds of one payment: exactly one must survive.
 *
 * <h2>It depends on READ COMMITTED, which is the default and is worth stating</h2>
 *
 * The loser's trigger runs a fresh SELECT at COMMIT and therefore sees the winner's committed row.
 * Under REPEATABLE READ it would not, and the check would pass for both -- the isolation level is
 * part of the guarantee, not an implementation detail.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class RefundConcurrencyTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:15:30Z");
    private static final Instant PROVIDER_EVENT = Instant.parse("2026-08-02T11:00:00Z");
    private static final long CAPTURED = 99900;

    @Autowired
    private CreateRefundService createRefundService;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private RecordProviderCallbackService paymentCallbacks;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcClient jdbc;

    /**
     * <b>Sabotage that must turn this red:</b> drop {@code tr_refunds_within_captured} from V16.
     * Every sequential over-refund test stays green, because the pre-check still catches those.
     */
    @Test
    void lettsExactlyOneOfTwoSimultaneousFullRefundsThrough() throws Exception {
        MerchantId merchantId = existingMerchant();
        String intentId = collected(merchantId);

        // A barrier rather than a sleep: both threads are released at the same instant, so neither
        // can have committed before the other reads.
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Callable<Boolean> fullRefund = () -> {
                startTogether.await();

                try {
                    createRefundService.create(new CreateRefundCommand(
                        merchantId, intentId, CAPTURED, null, "Simultaneous", "usr_1"
                    ));

                    return true;
                } catch (RuntimeException refused) {
                    return false;
                }
            };

            List<Future<Boolean>> results =
                List.of(pool.submit(fullRefund), pool.submit(fullRefund));

            long succeeded = 0;

            for (Future<Boolean> result : results) {
                if (result.get()) {
                    succeeded++;
                }
            }

            assertThat(succeeded)
                .as("two full refunds of one payment: exactly one may survive, whichever wins")
                .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(activeTotal(intentId))
            .as("and the money actually committed never exceeds what was captured")
            .isEqualTo(CAPTURED);
    }

    /** The same race with two halves that jointly overshoot by one minor unit. */
    @Test
    void refusesTwoSimultaneousHalvesThatJointlyOvershoot() throws Exception {
        MerchantId merchantId = existingMerchant();
        String intentId = collected(merchantId);

        long half = CAPTURED / 2 + 1;

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Callable<Boolean> halfRefund = () -> {
                startTogether.await();

                try {
                    createRefundService.create(new CreateRefundCommand(
                        merchantId, intentId, half, null, "Simultaneous half", "usr_1"
                    ));

                    return true;
                } catch (RuntimeException refused) {
                    return false;
                }
            };

            List<Future<Boolean>> results =
                List.of(pool.submit(halfRefund), pool.submit(halfRefund));

            long succeeded = 0;

            for (Future<Boolean> result : results) {
                if (result.get()) {
                    succeeded++;
                }
            }

            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(activeTotal(intentId)).isLessThanOrEqualTo(CAPTURED);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private long activeTotal(String intentId) {
        return jdbc.sql("""
                select coalesce(sum(amount_minor), 0) from refunds
                 where payment_intent_id = ? and status not in ('FAILED', 'CANCELLED')
                """)
            .param(intentId)
            .query(Long.class)
            .single();
    }

    private String collected(MerchantId merchantId) {
        Order order = orders.save(Order.create(
            OrderId.generate(), merchantId, null, "ORDER-" + UUID.randomUUID(),
            CAPTURED, "INR", null, Map.of(), null, CREATED_AT
        ));

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, order.orderId().value(), null, CAPTURED, "INR",
            CaptureMethod.AUTOMATIC, null, Map.of()
        ));

        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        paymentCallbacks.record(new RecordProviderCallbackCommand(
            "SIMULATOR",
            new ProviderEvent(
                "evt-" + UUID.randomUUID(), PROVIDER_EVENT, intent.paymentIntentId().value(),
                null, ProviderOutcome.SUCCEEDED, null, CAPTURED, null, null, null
            ),
            (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "")
        ));

        return intent.paymentIntentId().value();
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Refund Race Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        )).merchantId();
    }
}
