package com.paymesh.customer.application;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.customer.domain.CustomerStatus;
import com.paymesh.customer.domain.CustomerStatusChange;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Block and unblock a customer.
 *
 * <h2>THE DOMAIN COULD ALREADY EXPRESS THIS AND NOTHING COULD PRODUCE IT</h2>
 *
 * ADR-021 added {@code Customer.block} and {@code CustomerStatusChange}, and claimed in its
 * consequences that "a customer can be blocked". That was half true: the aggregate could represent
 * BLOCKED and no service or endpoint ever called the method, so the state stayed exactly as
 * unreachable as before -- the same defect ADR-021 exists to fix, one layer up. This is the service
 * that makes the claim true. ADR-023.
 *
 * <h2>Blocking is the merchant's decision, not the platform's</h2>
 *
 * Unlike a merchant suspension, PayMesh has no opinion: this is a business deciding it will not
 * sell to someone. So the actor is MERCHANT and a merchant-scoped caller may do it to their own
 * customers and nobody else's.
 */
public final class ChangeCustomerStatusService {

    private static final Logger log = LoggerFactory.getLogger(ChangeCustomerStatusService.class);

    private final CustomerRepository customers;
    private final CustomerStatusHistoryRepository history;
    private final GetCustomerService getCustomerService;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ChangeCustomerStatusService(
        CustomerRepository customers,
        CustomerStatusHistoryRepository history,
        GetCustomerService getCustomerService,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.customers = customers;
        this.history = history;
        this.getCustomerService = getCustomerService;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Customer block(MerchantId merchantId, CustomerId customerId, String actorId, String reason) {
        return change(merchantId, customerId, CustomerStatus.BLOCKED, actorId, reason);
    }

    public Customer unblock(MerchantId merchantId, CustomerId customerId, String actorId) {
        return change(merchantId, customerId, CustomerStatus.ACTIVE, actorId, null);
    }

    private Customer change(
        MerchantId merchantId,
        CustomerId customerId,
        CustomerStatus target,
        String actorId,
        String reason
    ) {
        Instant now = Instant.now(clock);

        return transactions.execute(status -> {
            Customer customer = getCustomerService.getById(merchantId, customerId);
            CustomerStatus from = customer.status();

            Customer changed = target == CustomerStatus.BLOCKED
                ? customer.block(now)
                : customer.unblock(now);

            Customer saved = customers.save(changed);

            history.append(new CustomerStatusChange(
                merchantId, customerId, from, saved.status(),
                CustomerStatusChange.ActorType.MERCHANT, actorId, reason, now
            ));

            log.info(
                "Customer status changed customerId={} merchantId={} from={} to={}",
                customerId.value(), merchantId.value(), from, saved.status()
            );

            return saved;
        });
    }
}
