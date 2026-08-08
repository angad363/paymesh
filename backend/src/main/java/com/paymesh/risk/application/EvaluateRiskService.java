package com.paymesh.risk.application;

import com.paymesh.risk.domain.DenylistHash;
import com.paymesh.risk.domain.RiskAssessment;
import com.paymesh.risk.domain.RiskFeatures;
import com.paymesh.risk.domain.RiskRuleset;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates one payment and records what it decided. SDD §14.
 *
 * <h2>WHAT THIS DELIBERATELY DOES NOT DO</h2>
 *
 * It does not touch the payment intent, does not write a state history row and does not emit an
 * event. SDD §14.2 is explicit: Risk returns a decision, Payment acts on it. That separation is
 * what keeps the money path's state machine in one place -- a Risk service that could fail a
 * payment would be a second author of payment status, and two authors of one state machine is how
 * a status becomes unexplainable.
 *
 * <h2>IT RUNS INSIDE THE CALLER'S TRANSACTION, ON PURPOSE</h2>
 *
 * {@code ConfirmPaymentIntentService} calls this after it has taken the intent's row lock and
 * before it moves the status. That means the assessment and the confirm commit together: there is
 * no window in which a payment was blocked but nothing recorded it, or recorded as allowed but
 * never confirmed.
 * <p>
 * <b>The cost, stated rather than hidden:</b> the velocity count and the denylist read happen while
 * that row lock is held. Both are single indexed queries against the same database, so this adds
 * two round trips to a transaction that already makes several -- unlike an HTTP call to a risk
 * vendor, which is the thing this shape would be wrong for. If Risk ever calls out of process, it
 * has to move out of this transaction and the confirm has to handle a decision that may not arrive.
 */
public final class EvaluateRiskService {

    private final RiskAssessmentRepository assessments;
    private final DenylistRepository denylist;
    private final PaymentVelocityLookup velocity;
    private final Duration velocityWindow;
    private final Clock clock;

    public EvaluateRiskService(
        RiskAssessmentRepository assessments,
        DenylistRepository denylist,
        PaymentVelocityLookup velocity,
        Duration velocityWindow,
        Clock clock
    ) {
        if (velocityWindow == null || velocityWindow.isZero() || velocityWindow.isNegative()) {
            throw new IllegalArgumentException("Risk velocity window must be positive");
        }

        this.assessments = assessments;
        this.denylist = denylist;
        this.velocity = velocity;
        this.velocityWindow = velocityWindow;
        this.clock = clock;
    }

    /**
     * @return the recorded assessment. Never null, and never silently absent: an evaluation that
     *     reached no conclusion would leave Payment guessing, so a failure here fails the confirm.
     */
    public RiskAssessment evaluate(EvaluateRiskCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Evaluate Risk Command cannot be null");
        }

        Instant now = Instant.now(clock);

        RiskFeatures features = new RiskFeatures(
            command.amountMinor(),
            command.currency(),
            command.customerId(),
            command.device(),
            intentsInWindow(command, now)
        );

        RiskRuleset.Verdict verdict = RiskRuleset.evaluate(
            features, isDenylisted(command, now)
        );

        return assessments.append(RiskAssessment.record(
            command.merchantId(), command.paymentIntentId(), verdict, features, now
        ));
    }

    /**
     * Zero for a guest checkout, and that is a real zero rather than an unknown: with no customer
     * there is nothing to count velocity against. Counting by device instead would look clever and
     * would be wrong -- a shared kiosk is one device and many people.
     */
    private int intentsInWindow(EvaluateRiskCommand command, Instant now) {
        if (command.customerId() == null) {
            return 0;
        }

        return velocity.intentsCreatedSince(
            command.merchantId(), command.customerId(), now.minus(velocityWindow)
        );
    }

    /**
     * One query for every candidate value this payment carries. Hashing happens here rather than in
     * the adapter because it is the domain's rule about how a denylist is keyed, and an adapter
     * that hashed differently would silently stop matching.
     */
    private boolean isDenylisted(EvaluateRiskCommand command, Instant now) {
        List<String> candidates = new ArrayList<>(2);

        if (command.customerId() != null) {
            candidates.add(DenylistHash.of(command.customerId()));
        }

        if (command.device() != null && !command.device().isBlank()) {
            candidates.add(DenylistHash.of(command.device()));
        }

        return !candidates.isEmpty()
            && denylist.matchesAny(command.merchantId(), candidates, now);
    }
}
