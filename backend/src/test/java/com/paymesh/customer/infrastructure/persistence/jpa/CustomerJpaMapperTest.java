package com.paymesh.customer.infrastructure.persistence.jpa;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.customer.domain.CustomerStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerJpaMapperTest {

    @Test
    void roundTripsEveryFieldThroughTheRowAndBack() {
        Customer customer = Customer.create(
            CustomerId.generate(),
            MerchantId.generate(),
            "user-42",
            "buyer@example.com",
            "Asha Rao",
            "+919876543210",
            Instant.parse("2026-07-19T10:15:30Z")
        );

        Customer mapped = CustomerJpaMapper.toDomain(CustomerJpaMapper.toEntity(customer));

        assertThat(mapped).usingRecursiveComparison().isEqualTo(customer);
    }

    @Test
    void roundTripsNullOptionalFields() {
        Customer customer = Customer.create(
            CustomerId.generate(),
            MerchantId.generate(),
            null,
            "buyer@example.com",
            null,
            null,
            Instant.parse("2026-07-19T10:15:30Z")
        );

        Customer mapped = CustomerJpaMapper.toDomain(CustomerJpaMapper.toEntity(customer));

        assertThat(mapped).usingRecursiveComparison().isEqualTo(customer);
        assertThat(mapped.phoneHash()).isNull();
    }

    @Test
    void writesTheStatusAsItsNameNotItsOrdinal() {
        Customer customer = Customer.reconstitute(
            CustomerId.generate(),
            MerchantId.generate(),
            null,
            "buyer@example.com",
            "hash",
            null,
            null,
            null,
            CustomerStatus.BLOCKED,
            Instant.parse("2026-07-19T10:15:30Z"),
            Instant.parse("2026-07-19T10:15:30Z")
        );

        assertThat(CustomerJpaMapper.toEntity(customer).status()).isEqualTo("BLOCKED");
    }
}
