package com.paymesh.order.infrastructure.customer;

import com.paymesh.customer.application.CustomerNotFoundException;
import com.paymesh.customer.application.GetCustomerService;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.order.application.CustomerLookup;
import com.paymesh.shared.tenant.MerchantId;

/**
 * THE ONLY CLASS IN {@code com.paymesh.order} THAT IMPORTS {@code com.paymesh.customer}.
 * <p>
 * Everything else in this module depends on the {@link CustomerLookup} port instead, which is what
 * makes extracting Customer into its own service a change to this file and nothing else (ADR-008).
 * The import check is worth keeping honest by hand until an architecture test enforces it.
 * <p>
 * The lookup is tenant-scoped through GetCustomerService, which answers "not found" for a customer
 * belonging to another merchant. Both cases therefore return false here, and the caller cannot tell
 * "no such customer" from "not yours" -- which is the point: the order endpoint must not become an
 * oracle for another tenant's customer ids.
 */
public final class CustomerModuleLookup implements CustomerLookup {

    private final GetCustomerService customers;

    public CustomerModuleLookup(GetCustomerService customers) {
        this.customers = customers;
    }

    @Override
    public boolean exists(MerchantId merchantId, String customerId) {
        CustomerId parsed;

        try {
            parsed = CustomerId.from(customerId);
        } catch (IllegalArgumentException exception) {
            // A malformed id cannot name a customer, so it gets the same answer as one that names
            // nobody. Reporting it as a distinct kind of error would confirm the id format is the
            // only thing wrong with it.
            return false;
        }

        try {
            customers.getById(merchantId, parsed);
            return true;
        } catch (CustomerNotFoundException exception) {
            return false;
        }
    }
}
