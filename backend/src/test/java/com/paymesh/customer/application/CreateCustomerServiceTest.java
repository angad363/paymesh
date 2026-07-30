package com.paymesh.customer.application;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerStatus;
import com.paymesh.merchant.domain.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateCustomerServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-19T10:15:30Z");

    private final CustomerRepository repository = new InMemoryCustomerRepository();
    private final CreateCustomerService service =
        new CreateCustomerService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsAnActiveCustomerStampedWithTheInjectedClock() {
        MerchantId merchantId = MerchantId.generate();

        Customer customer = service.create(
            new CreateCustomerCommand(merchantId, "user-42", "Buyer@Example.com", "Asha Rao", null)
        );

        assertEquals(merchantId, customer.merchantId());
        assertEquals("buyer@example.com", customer.email());
        assertEquals(CustomerStatus.ACTIVE, customer.status());
        assertEquals(NOW, customer.createdAt());
        assertTrue(customer.customerId().value().startsWith("cus_"));
    }

    @Test
    void mintsADistinctIdentifierPerCustomer() {
        MerchantId merchantId = MerchantId.generate();

        Customer first = service.create(command(merchantId, null));
        Customer second = service.create(command(merchantId, null));

        assertNotEquals(first.customerId(), second.customerId());
    }

    @Test
    void rejectsAMerchantReferenceAlreadyUsedByTheSameMerchant() {
        MerchantId merchantId = MerchantId.generate();
        service.create(command(merchantId, "user-42"));

        assertThrows(
            CustomerReferenceAlreadyExistsException.class,
            () -> service.create(command(merchantId, "user-42"))
        );
    }

    /**
     * merchant_reference is the MERCHANT's own key, so two merchants both calling their customer
     * "user-42" is normal, not a conflict.
     */
    @Test
    void allowsTheSameMerchantReferenceUnderADifferentMerchant() {
        service.create(command(MerchantId.generate(), "user-42"));

        Customer other = service.create(command(MerchantId.generate(), "user-42"));

        assertEquals("user-42", other.merchantReference());
    }

    @Test
    void allowsManyCustomersWithoutAMerchantReference() {
        MerchantId merchantId = MerchantId.generate();

        service.create(command(merchantId, null));
        service.create(command(merchantId, null));
        service.create(command(merchantId, "   "));
    }

    /**
     * The uniqueness check must run against the normalized reference, not the raw input, or
     * " user-42 " would slip past a check that the database then rejects with a 500.
     */
    @Test
    void checksUniquenessAgainstTheNormalizedMerchantReference() {
        MerchantId merchantId = MerchantId.generate();
        service.create(command(merchantId, "user-42"));

        assertThrows(
            CustomerReferenceAlreadyExistsException.class,
            () -> service.create(command(merchantId, "  user-42  "))
        );
    }

    @Test
    void rejectsNullCommand() {
        assertThrows(IllegalArgumentException.class, () -> service.create(null));
    }

    @Test
    void rejectsACommandWithoutAMerchant() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.create(command(null, null))
        );
    }

    private static CreateCustomerCommand command(MerchantId merchantId, String merchantReference) {
        return new CreateCustomerCommand(merchantId, merchantReference, "buyer@example.com", null, null);
    }
}
