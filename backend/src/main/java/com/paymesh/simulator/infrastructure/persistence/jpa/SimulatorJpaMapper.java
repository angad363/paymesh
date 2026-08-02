package com.paymesh.simulator.infrastructure.persistence.jpa;

import com.paymesh.simulator.domain.FailureProfile;
import com.paymesh.simulator.domain.OutboundCallback;
import com.paymesh.simulator.domain.OutboundCallbackStatus;
import com.paymesh.simulator.domain.RefundStatus;
import com.paymesh.simulator.domain.SimulatedBehaviour;
import com.paymesh.simulator.domain.SimulatedCaptureMethod;
import com.paymesh.simulator.domain.SimulatedMethod;
import com.paymesh.simulator.domain.SimulatedOutcome;
import com.paymesh.simulator.domain.SimulatedPayment;
import com.paymesh.simulator.domain.SimulatedPaymentId;
import com.paymesh.simulator.domain.SimulatedPaymentStatus;
import com.paymesh.simulator.domain.SimulatedRefund;
import com.paymesh.simulator.domain.SimulatedRefundId;

import java.time.Duration;

/**
 * Hand-written translation between the simulator's aggregates and its rows (ADR-004).
 * <p>
 * One class for all four tables rather than four classes, because they share nothing but this
 * direction of travel and four files of six lines each would be filing rather than design.
 * <p>
 * Enums cross as their {@code name()}, never their ordinal. An ordinal is positional, so inserting a
 * value into the middle of an enum silently rewrites the meaning of every stored row -- and the
 * CHECK constraints in V13 are written against the names anyway.
 */
final class SimulatorJpaMapper {

    private SimulatorJpaMapper() {
    }

    static SimulatedPaymentJpaEntity toEntity(SimulatedPayment payment) {
        return new SimulatedPaymentJpaEntity(
            payment.providerPaymentId().value(),
            payment.idempotencyKey(),
            payment.requestHash(),
            payment.callbackReference(),
            payment.method().name(),
            payment.token(),
            payment.behaviour().name(),
            payment.amountMinor(),
            payment.currency(),
            payment.captureMethod().name(),
            payment.status().name(),
            payment.capturedAmountMinor(),
            payment.refundedAmountMinor(),
            payment.failureCode(),
            payment.failureMessage(),
            payment.createdAt(),
            payment.updatedAt()
        );
    }

    static SimulatedPayment toDomain(SimulatedPaymentJpaEntity entity) {
        return SimulatedPayment.rehydrate(
            SimulatedPaymentId.from(entity.providerPaymentId()),
            entity.idempotencyKey(),
            entity.requestHash(),
            entity.callbackReference(),
            SimulatedMethod.valueOf(entity.method()),
            entity.token(),
            SimulatedBehaviour.valueOf(entity.behaviour()),
            entity.amountMinor(),
            entity.currency(),
            SimulatedCaptureMethod.valueOf(entity.captureMethod()),
            SimulatedPaymentStatus.valueOf(entity.status()),
            entity.capturedAmountMinor(),
            entity.refundedAmountMinor(),
            entity.failureCode(),
            entity.failureMessage(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }

    static SimulatedRefundJpaEntity toEntity(SimulatedRefund refund) {
        return new SimulatedRefundJpaEntity(
            refund.providerRefundId().value(),
            refund.providerPaymentId().value(),
            refund.idempotencyKey(),
            refund.requestHash(),
            refund.amountMinor(),
            refund.status().name(),
            refund.failureCode(),
            refund.failureMessage(),
            refund.createdAt(),
            refund.updatedAt()
        );
    }

    static SimulatedRefund toDomain(SimulatedRefundJpaEntity entity) {
        return new SimulatedRefund(
            SimulatedRefundId.from(entity.providerRefundId()),
            SimulatedPaymentId.from(entity.providerPaymentId()),
            entity.idempotencyKey(),
            entity.requestHash(),
            entity.amountMinor(),
            RefundStatus.valueOf(entity.status()),
            entity.failureCode(),
            entity.failureMessage(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }

    static OutboundCallbackJpaEntity toEntity(OutboundCallback callback) {
        return new OutboundCallbackJpaEntity(
            callback.outboundCallbackId(),
            callback.externalEventId(),
            callback.providerPaymentId().value(),
            callback.callbackReference(),
            callback.outcome().name(),
            callback.occurredAt(),
            callback.deliverAfter(),
            callback.body(),
            callback.status().name(),
            callback.attempts(),
            callback.lastAttemptAt(),
            callback.lastResponseStatus(),
            callback.lastResponseOutcome(),
            callback.createdAt(),
            callback.updatedAt()
        );
    }

    static OutboundCallback toDomain(OutboundCallbackJpaEntity entity) {
        return OutboundCallback.rehydrate(
            entity.outboundCallbackId(),
            entity.externalEventId(),
            SimulatedPaymentId.from(entity.providerPaymentId()),
            entity.callbackReference(),
            SimulatedOutcome.valueOf(entity.outcome()),
            entity.occurredAt(),
            entity.deliverAfter(),
            entity.body(),
            OutboundCallbackStatus.valueOf(entity.status()),
            entity.attempts(),
            entity.lastAttemptAt(),
            entity.lastResponseStatus(),
            entity.lastResponseOutcome(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }

    static FailureProfileJpaEntity toEntity(FailureProfile profile) {
        return new FailureProfileJpaEntity(
            FailureProfile.PROFILE_ID,
            profile.defaultBehaviour().name(),
            // Milliseconds, because the column is an INTEGER of them. A Duration longer than
            // ~24 days would overflow, and nothing in a failure profile wants one.
            Math.toIntExact(profile.callbackDelay().toMillis()),
            profile.updatedAt()
        );
    }

    static FailureProfile toDomain(FailureProfileJpaEntity entity) {
        return new FailureProfile(
            SimulatedBehaviour.valueOf(entity.defaultBehaviour()),
            Duration.ofMillis(entity.callbackDelayMs()),
            entity.updatedAt()
        );
    }
}
