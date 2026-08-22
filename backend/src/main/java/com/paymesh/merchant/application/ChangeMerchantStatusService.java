package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantStatus;
import com.paymesh.merchant.domain.MerchantStatusChange;
import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.audit.AuditEntry;
import com.paymesh.shared.audit.AuditRecorder;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Activate, suspend, close. THE CONTROL THE PLATFORM DID NOT HAVE.
 *
 * <h2>PLATFORM STAFF ONLY, AND THE CONTROLLER ENFORCES IT</h2>
 *
 * A merchant lifting their own suspension would make suspension advisory, so the routes require
 * {@code PLATFORM_ADMIN} and this service is only reachable from them. It takes the operator's id
 * as an argument rather than reading it from a context, so the audit row cannot be written without
 * one -- and {@code MerchantStatusChange} refuses a PLATFORM change with no actor.
 *
 * <h2>Every transition is logged at WARN, including activation</h2>
 *
 * Not INFO. These are the rarest and most consequential writes the platform accepts, and an
 * operator scanning logs after an incident should not have to filter them out of ordinary traffic.
 */
public final class ChangeMerchantStatusService {

    private static final Logger log = LoggerFactory.getLogger(ChangeMerchantStatusService.class);

    private final MerchantRepository merchants;
    private final MerchantStatusHistoryRepository history;
    private final GetMerchantService getMerchantService;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ChangeMerchantStatusService(
        MerchantRepository merchants,
        MerchantStatusHistoryRepository history,
        GetMerchantService getMerchantService,
        AuditRecorder auditRecorder,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.merchants = merchants;
        this.history = history;
        this.getMerchantService = getMerchantService;
        this.auditRecorder = auditRecorder;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Whether this merchant is already able to trade. Lets a caller avoid a redundant transition. */
    public boolean canTransact(MerchantId merchantId) {
        return getMerchantService.getById(merchantId).canTransact();
    }

    public Merchant activate(MerchantId merchantId, String operatorId, String reason) {
        return change(merchantId, MerchantStatus.ACTIVE, operatorId, reason);
    }

    public Merchant suspend(MerchantId merchantId, String operatorId, String reason) {
        return change(merchantId, MerchantStatus.SUSPENDED, operatorId, reason);
    }

    public Merchant close(MerchantId merchantId, String operatorId, String reason) {
        return change(merchantId, MerchantStatus.CLOSED, operatorId, reason);
    }

    private Merchant change(
        MerchantId merchantId,
        MerchantStatus target,
        String operatorId,
        String reason
    ) {
        Instant now = Instant.now(clock);

        return transactions.execute(status -> {
            Merchant merchant = getMerchantService.getById(merchantId);
            MerchantStatus from = merchant.status();

            Merchant changed = switch (target) {
                case ACTIVE -> merchant.activate(now);
                case SUSPENDED -> merchant.suspend(now);
                case CLOSED -> merchant.close(now);
                case PENDING_VERIFICATION -> throw new IllegalArgumentException(
                    "A merchant cannot be returned to PENDING_VERIFICATION"
                );
            };

            Merchant saved = merchants.save(changed);

            history.append(new MerchantStatusChange(
                merchantId, from, saved.status(),
                MerchantStatusChange.ActorType.PLATFORM, operatorId, reason, now
            ));

            // The audit event, inside this same transaction, so a committed status change always
            // carries it (ADR-035). This deliberately overlaps merchant_status_history: that table
            // is Merchant's own record of ITS transitions; the audit log is the ONE place a
            // compliance reviewer reads every privileged action across every capability, immutable
            // by trigger. The overlap is the same shape as Reporting restating Ledger figures.
            auditRecorder.record(
                AuditEntry.builder("merchant." + action(saved.status()), ActorType.USER)
                    .actorId(operatorId)
                    .merchant(merchantId)
                    .resource("merchant", merchantId.value())
                    .reason(reason)
                    .changing(from.name(), saved.status().name())
                    .build()
            );

            log.warn(
                "Merchant status changed merchantId={} from={} to={} operator={} reason={}",
                merchantId.value(), from, saved.status(), operatorId, reason
            );

            return saved;
        });
    }

    /** The audit action verb for a reached status: SUSPENDED -> "suspended", ACTIVE -> "activated". */
    private static String action(MerchantStatus status) {
        return switch (status) {
            case ACTIVE -> "activated";
            case SUSPENDED -> "suspended";
            case CLOSED -> "closed";
            case PENDING_VERIFICATION -> "pending_verification";
        };
    }
}
