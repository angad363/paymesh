package com.paymesh.ledger.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.ChangeMerchantStatusService;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
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
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/balances}.
 * <p>
 * Not {@code @Transactional}: the balance only exists after the relay has delivered
 * {@code payment.succeeded} and the Ledger's consumer has committed a journal, which a rolled-back
 * test transaction would undo.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class BalanceControllerTest {

    /** Platform staff, for fixtures that must activate a merchant. */
    private static final String PLATFORM_OPERATOR = "usr_00000000-0000-4000-8000-000000000001";

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:15:30Z");
    private static final Instant PROVIDER_EVENT = Instant.parse("2026-08-02T11:00:00Z");
    private static final String PROVIDER = "SIMULATOR";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisterMerchantService merchants;

    @Autowired
    private ChangeMerchantStatusService changeMerchantStatus;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private RecordProviderCallbackService callbacks;

    @Autowired
    private PublishOutboxEventsService relay;

    @Test
    void returnsThePendingBalanceInMinorUnits() throws Exception {
        MerchantId merchantId = registerMerchant();
        collect(merchantId, 99900, "INR");

        mockMvc.perform(get("/api/v1/balances").with(callerFor(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balances.length()").value(1))
            .andExpect(jsonPath("$.balances[0].currency").value("INR"))
            .andExpect(jsonPath("$.balances[0].pendingMinor").value(99900));
    }

    /**
     * INTEGER MINOR UNITS, NOT A DECIMAL. 99900 is 999.00 and the JSON says 99900. A decimal here
     * would be the money convention broken at the one endpoint whose entire job is reporting money.
     */
    @Test
    void reportsMoneyWithNoDecimalPoint() throws Exception {
        MerchantId merchantId = registerMerchant();
        collect(merchantId, 99900, "INR");

        mockMvc.perform(get("/api/v1/balances").with(callerFor(merchantId)))
            .andExpect(jsonPath("$.balances[0].pendingMinor").value(99900))
            .andExpect(jsonPath("$.balances[0].pendingMinor").isNumber());
    }

    /**
     * SDD 15.3's available/reserved/in-settlement figures are OMITTED, not returned as zero. A zero
     * {@code availableMinor} would claim this merchant has nothing they can withdraw; the truth is
     * that "available" is not a concept this ledger has yet.
     */
    @Test
    void omitsTheBalancesSettlementHasNotMadeRealYet() throws Exception {
        MerchantId merchantId = registerMerchant();
        collect(merchantId, 99900, "INR");

        mockMvc.perform(get("/api/v1/balances").with(callerFor(merchantId)))
            .andExpect(jsonPath("$.balances[0].availableMinor").doesNotExist())
            .andExpect(jsonPath("$.balances[0].reservedMinor").doesNotExist())
            .andExpect(jsonPath("$.balances[0].inSettlementMinor").doesNotExist());
    }

    /** A merchant who has never been paid has a balance of nothing, which is not a 404. */
    @Test
    void returnsAnEmptyListForAMerchantWithNoPayments() throws Exception {
        mockMvc.perform(get("/api/v1/balances").with(callerFor(registerMerchant())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balances.length()").value(0));
    }

    /**
     * TENANT ISOLATION, AND THE REQUEST CANNOT EVEN EXPRESS THE VIOLATION. There is no merchant
     * parameter to tamper with -- the tenant comes from the verified token -- so a second merchant
     * asking for balances gets their own, which are none.
     */
    @Test
    void neverShowsOneMerchantAnotherMerchantsMoney() throws Exception {
        MerchantId paid = registerMerchant();
        collect(paid, 99900, "INR");

        mockMvc.perform(get("/api/v1/balances").with(callerFor(registerMerchant())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balances.length()").value(0));
    }

    @Test
    void returnsOneRowPerCurrency() throws Exception {
        MerchantId merchantId = registerMerchant();
        collect(merchantId, 99900, "INR");
        collect(merchantId, 4200, "USD");

        mockMvc.perform(get("/api/v1/balances").with(callerFor(merchantId)))
            .andExpect(jsonPath("$.balances.length()").value(2))
            .andExpect(jsonPath("$.balances[0].currency").value("INR"))
            .andExpect(jsonPath("$.balances[1].currency").value("USD"));
    }

    /** Nothing under /api/v1 is open by accident. */
    @Test
    void refusesAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(get("/api/v1/balances"))
            .andExpect(status().isUnauthorized());
    }

    /** A token with no merchant scope cannot be resolved to a tenant, and the ledger must not guess. */
    @Test
    void refusesACallerWithNoMerchantScope() throws Exception {
        RequestPostProcessor scopeless = jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of()));

        mockMvc.perform(get("/api/v1/balances").with(scopeless))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NO_MERCHANT_SCOPE"));
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Order → intent → attach → confirm → provider says SUCCEEDED → relay → the ledger posts. */
    private void collect(MerchantId merchantId, long amountMinor, String currency) {
        Order order = orders.save(Order.create(
            OrderId.generate(), merchantId, null, "ORDER-" + UUID.randomUUID(),
            amountMinor, currency, null, Map.of(), null, CREATED_AT
        ));

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, order.orderId().value(), null, amountMinor, currency,
            CaptureMethod.AUTOMATIC, null, Map.of()
        ));

        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        callbacks.record(new RecordProviderCallbackCommand(
            PROVIDER,
            new ProviderEvent(
                "evt-succeed-" + UUID.randomUUID(), PROVIDER_EVENT, intent.paymentIntentId().value(),
                null, ProviderOutcome.SUCCEEDED, null, amountMinor, null, null, null
            ),
            (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "")
        ));

        while (relay.publish().published() > 0) {
            // the suite shares one container; a single pass can be consumed by another test's backlog
        }
    }

    private static RequestPostProcessor callerFor(MerchantId merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId.value())));
    }

    private MerchantId registerMerchant() {
        MerchantId merchantId = merchants.register(new RegisterMerchantCommand(
            "Balance Test Co", "balance-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();
        activate(merchantId);

        return merchantId;
    }

    /**
     * REGISTRATION PRODUCES PENDING_VERIFICATION, AND A PENDING MERCHANT CANNOT WRITE (ADR-021).
     * <p>
     * Every fixture that goes on to create an order, an intent or a refund has to take the merchant
     * through the real activation path first, exactly as a platform operator would. Skipping it
     * would mean these tests exercised a merchant state no live merchant can be in.
     */
    private void activate(MerchantId merchantId) {
        changeMerchantStatus.activate(merchantId, PLATFORM_OPERATOR, "Activated for test");
    }
}
