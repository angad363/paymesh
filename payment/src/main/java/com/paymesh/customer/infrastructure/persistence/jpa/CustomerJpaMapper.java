package com.paymesh.customer.infrastructure.persistence.jpa;

import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.customer.domain.CustomerStatus;
import com.paymesh.shared.tenant.MerchantId;

/**
 * Translates between the domain aggregate and the persistence row, in both directions.
 * Explicit and field-by-field so a schema change surfaces as a compile error, not as lost data.
 */
public final class CustomerJpaMapper {

    private CustomerJpaMapper() {
    }

    public static CustomerJpaEntity toEntity(Customer customer) {
        return new CustomerJpaEntity(
            customer.customerId().value(),
            customer.merchantId().value(),
            customer.merchantReference(),
            customer.email(),
            customer.emailHash(),
            customer.name(),
            customer.phone(),
            customer.phoneHash(),
            customer.status().name(),
            customer.createdAt(),
            customer.updatedAt()
        );
    }

    public static Customer toDomain(CustomerJpaEntity entity) {
        return Customer.reconstitute(
            CustomerId.from(entity.customerId()),
            MerchantId.from(entity.merchantId()),
            entity.merchantReference(),
            entity.email(),
            entity.emailHash(),
            entity.name(),
            entity.phone(),
            entity.phoneHash(),
            CustomerStatus.valueOf(entity.status()),
            entity.createdAt(),
            entity.updatedAt()
        );
    }
}
