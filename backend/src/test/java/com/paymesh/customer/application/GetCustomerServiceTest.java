package com.paymesh.customer.application;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.merchant.domain.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GetCustomerServiceTest {

    private final CustomerRepository repository = new InMemoryCustomerRepository();
    private final GetCustomerService service = new GetCustomerService(repository);

    @Test
    void returnsCustomerWhenItBelongsToTheMerchant() {
        MerchantId merchantId = MerchantId.generate();
        Customer customer = repository.save(createCustomer(merchantId));

        Customer result = service.getById(merchantId, customer.customerId());

        assertSame(customer, result);
    }

    @Test
    void throwsWhenCustomerDoesNotExist() {
        assertThrows(
            CustomerNotFoundException.class,
            () -> service.getById(MerchantId.generate(), CustomerId.generate())
        );
    }

    /**
     * The tenant isolation rule in one test: holding a valid customer id is not authorization.
     * Another merchant asking for it gets the same "not found" as if the row never existed, so the
     * response never confirms that the id is real.
     */
    @Test
    void throwsNotFoundWhenTheCustomerBelongsToAnotherMerchant() {
        Customer customer = repository.save(createCustomer(MerchantId.generate()));
        MerchantId otherMerchantId = MerchantId.generate();

        assertThrows(
            CustomerNotFoundException.class,
            () -> service.getById(otherMerchantId, customer.customerId())
        );
    }

    @Test
    void rejectsNullMerchantId() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.getById(null, CustomerId.generate())
        );
    }

    @Test
    void rejectsNullCustomerId() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.getById(MerchantId.generate(), null)
        );
    }

    private static Customer createCustomer(MerchantId merchantId) {
        return Customer.create(
            CustomerId.generate(),
            merchantId,
            "user-42",
            "buyer@example.com",
            "Asha Rao",
            null,
            Instant.parse("2026-07-19T10:15:30Z")
        );
    }
}
