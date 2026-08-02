package com.paymesh.customer.infrastructure.persistence.jpa;

import com.paymesh.customer.application.PaymentMethodAlreadyAttachedException;
import com.paymesh.customer.application.PaymentMethodTokenRepository;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.customer.domain.PaymentMethodToken;
import com.paymesh.customer.domain.PaymentMethodTokenId;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

public final class JpaPaymentMethodTokenRepository implements PaymentMethodTokenRepository {

    private final SpringDataPaymentMethodTokenRepository tokens;

    public JpaPaymentMethodTokenRepository(SpringDataPaymentMethodTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Override
    public PaymentMethodToken save(PaymentMethodToken token) {
        try {
            return toDomain(tokens.saveAndFlush(toEntity(token)));
        } catch (DataIntegrityViolationException exception) {
            // TWO CONSTRAINTS CAN FIRE HERE AND THEY MEAN DIFFERENT THINGS.
            //
            // uq_payment_method_tokens_provider_token (V3) is NOT partial: a provider token is
            // unique for a merchant and provider forever, detached or not. So re-attaching a card
            // after detaching it needs a FRESH token from the provider -- which is what would
            // happen in reality anyway.
            //
            // The live-fingerprint index (V19) is partial and means the same CARD is already on
            // file. Telling a caller the wrong one sends them looking at a card that is not there.
            if (namesConstraint(exception, "uq_payment_method_tokens_provider_token")) {
                throw PaymentMethodAlreadyAttachedException.sameProviderToken(
                    token.customerId().value()
                );
            }

            throw PaymentMethodAlreadyAttachedException.sameCard(token.customerId().value());
        }
    }

    @Override
    public Optional<PaymentMethodToken> findById(MerchantId merchantId, PaymentMethodTokenId id) {
        return tokens.findByMerchantIdAndPaymentMethodTokenId(merchantId.value(), id.value())
            .map(JpaPaymentMethodTokenRepository::toDomain);
    }

    @Override
    public List<PaymentMethodToken> findLiveByCustomer(MerchantId merchantId, CustomerId customerId) {
        return tokens
            .findByMerchantIdAndCustomerIdAndDetachedAtIsNullOrderByCreatedAtDesc(
                merchantId.value(), customerId.value()
            )
            .stream()
            .map(JpaPaymentMethodTokenRepository::toDomain)
            .toList();
    }

    /** The partial fingerprint index does not always surface with a name; the V3 one does. */
    private static boolean namesConstraint(RuntimeException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(constraintName)) {
                return true;
            }

            if (cause.getCause() == cause) {
                break;
            }
        }

        return false;
    }

    private static PaymentMethodTokenJpaEntity toEntity(PaymentMethodToken token) {
        return new PaymentMethodTokenJpaEntity(
            token.paymentMethodTokenId().value(),
            token.merchantId().value(),
            token.customerId().value(),
            token.provider(),
            token.providerToken(),
            token.fingerprint(),
            token.brand(),
            token.lastFour(),
            token.expiryMonth() == null ? null : token.expiryMonth().shortValue(),
            token.expiryYear() == null ? null : token.expiryYear().shortValue(),
            token.detachedAt(),
            token.createdAt()
        );
    }

    private static PaymentMethodToken toDomain(PaymentMethodTokenJpaEntity entity) {
        return new PaymentMethodToken(
            PaymentMethodTokenId.from(entity.paymentMethodTokenId()),
            MerchantId.from(entity.merchantId()),
            CustomerId.from(entity.customerId()),
            entity.provider(),
            entity.providerToken(),
            entity.fingerprint(),
            entity.brand(),
            entity.lastFour(),
            entity.expiryMonth() == null ? null : entity.expiryMonth().intValue(),
            entity.expiryYear() == null ? null : entity.expiryYear().intValue(),
            entity.detachedAt(),
            entity.createdAt()
        );
    }
}
