package com.paymesh.customer.application;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.customer.domain.PaymentMethodToken;
import com.paymesh.customer.domain.PaymentMethodTokenId;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Attach and detach a card. SDD 10.3, and the first writer {@code payment_method_tokens} has ever
 * had (ADR-023).
 *
 * <h2>A BLOCKED CUSTOMER MAY NOT HAVE A CARD ATTACHED</h2>
 *
 * Attaching is the first step of charging somebody, and a merchant who has blocked a customer has
 * said they will not sell to them. Detaching stays allowed in both states -- removing a card from a
 * blocked customer is exactly what a merchant would want to do next.
 */
public final class AttachPaymentMethodTokenService {

    private static final int ATTACHED_VERSION = 1;

    private final PaymentMethodTokenRepository tokens;
    private final GetCustomerService getCustomerService;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AttachPaymentMethodTokenService(
        PaymentMethodTokenRepository tokens,
        GetCustomerService getCustomerService,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.tokens = tokens;
        this.getCustomerService = getCustomerService;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public PaymentMethodToken attach(
        MerchantId merchantId,
        CustomerId customerId,
        String provider,
        String providerToken,
        String fingerprint,
        String brand,
        String lastFour,
        Integer expiryMonth,
        Integer expiryYear
    ) {
        Customer customer = getCustomerService.getById(merchantId, customerId);

        if (!customer.canBeCharged()) {
            throw new CustomerNotChargeableException(customerId.value());
        }

        Instant now = Instant.now(clock);

        // THE ROW AND ITS EVENT COMMIT TOGETHER, which is ADR-010 and was missing here -- found in
        // review. Without the transaction the two are separate auto-commits, so a crash between
        // them leaves either a card on file that nothing was told about, or an event announcing a
        // card that does not exist. The whole outbox pattern is that neither is representable.
        return transactions.execute(status -> {
            PaymentMethodToken saved = tokens.save(PaymentMethodToken.attach(
                merchantId, customerId, provider, providerToken, fingerprint,
                brand, lastFour, expiryMonth, expiryYear, now
            ));

            outbox.append(attached(saved, now));

            return saved;
        });
    }

    /** Allowed whatever the customer's status: removing a blocked customer's card is reasonable. */
    public PaymentMethodToken detach(MerchantId merchantId, PaymentMethodTokenId tokenId) {
        PaymentMethodToken token = tokens.findById(merchantId, tokenId)
            .orElseThrow(() -> new PaymentMethodTokenNotFoundException(tokenId.value()));

        return tokens.save(token.detach(Instant.now(clock)));
    }

    public List<PaymentMethodToken> list(MerchantId merchantId, CustomerId customerId) {
        // Reading through the customer first, so another merchant's customer is a 404 rather than
        // an empty list -- an empty list would confirm the customer id is at least well formed.
        getCustomerService.getById(merchantId, customerId);

        return tokens.findLiveByCustomer(merchantId, customerId);
    }

    /**
     * {@code customer.payment_method.attached}, SDD 10.5's name. Nothing consumes it yet; it is
     * emitted because the outbox write is what makes the fact recoverable -- a consumer added later
     * reads the backlog rather than starting blind.
     * <p>
     * THE PAYLOAD CARRIES NO PROVIDER TOKEN. An event is the most widely-copied artefact in the
     * system: it goes to the outbox, to every consumer, and one day to a broker's log. The display
     * details are enough for anything downstream to say which card, and the token is the one thing
     * that could charge it.
     */
    private static OutboxEvent attached(PaymentMethodToken token, Instant now) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentMethodTokenId", token.paymentMethodTokenId().value());
        payload.put("merchantId", token.merchantId().value());
        payload.put("customerId", token.customerId().value());
        payload.put("provider", token.provider());
        payload.put("brand", token.brand());
        payload.put("lastFour", token.lastFour());
        payload.put("occurredAt", now.toString());

        return new OutboxEvent(
            EventId.generate(),
            token.merchantId(),
            "CUSTOMER",
            token.customerId().value(),
            "customer.payment_method.attached",
            ATTACHED_VERSION,
            payload,
            now
        );
    }
}
