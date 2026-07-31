package com.paymesh.order.infrastructure.persistence.jpa;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.customer.application.CustomerRepository;
import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderCursor;
import com.paymesh.order.application.OrderReferenceAlreadyExistsException;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@Transactional
class JpaOrderRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-31T10:15:30Z");

    @Autowired
    private OrderRepository repository;

    /**
     * orders.merchant_id is foreign-keyed to merchants, so a tenant has to exist before any of its
     * orders can. That constraint is part of what is under test here.
     */
    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private CustomerRepository customers;

    @Test
    void savesAndReadsBackAnOrder() {
        MerchantId merchantId = existingMerchant();
        Order saved = repository.save(order(merchantId, "ORDER-7788"));

        Order found = repository.findByOrderId(merchantId, saved.orderId()).orElseThrow();

        assertThat(found).usingRecursiveComparison().isEqualTo(saved);
    }

    /** JSONB round trip: what the merchant sent back is what the merchant gets back. */
    @Test
    void persistsMetadataAsJson() {
        MerchantId merchantId = existingMerchant();
        Map<String, String> metadata = Map.of("channel", "web", "campaign", "summer-sale");

        Order saved = repository.save(Order.create(
            OrderId.generate(), merchantId, null, null, 1999, "INR", "Two teas", metadata,
            CREATED_AT.plusSeconds(3600), CREATED_AT
        ));

        assertThat(repository.findByOrderId(merchantId, saved.orderId()).orElseThrow().metadata())
            .isEqualTo(metadata);
    }

    @Test
    void returnsEmptyWhenOrderIdIsUnknown() {
        assertThat(repository.findByOrderId(existingMerchant(), OrderId.generate())).isEmpty();
    }

    /**
     * The isolation invariant, proved against the real query: merchant B holds a valid order id
     * belonging to merchant A and still gets nothing back. If the adapter ever fell back to
     * findById(orderId) this is the test that fails.
     */
    @Test
    void doesNotReturnAnOrderOwnedByAnotherMerchant() {
        MerchantId merchantA = existingMerchant();
        MerchantId merchantB = existingMerchant();
        Order orderOfA = repository.save(order(merchantA, null));

        assertThat(repository.findByOrderId(merchantB, orderOfA.orderId())).isEmpty();
    }

    // --- the merchant's own reference ------------------------------------------

    @Test
    void scopesTheReferenceExistenceCheckToTheMerchant() {
        MerchantId merchantA = existingMerchant();
        MerchantId merchantB = existingMerchant();
        repository.save(order(merchantA, "ORDER-7788"));

        assertThat(repository.existsByMerchantOrderReference(merchantA, "ORDER-7788")).isTrue();
        assertThat(repository.existsByMerchantOrderReference(merchantB, "ORDER-7788")).isFalse();
    }

    @Test
    void allowsTheSameMerchantOrderReferenceUnderDifferentMerchants() {
        Order first = repository.save(order(existingMerchant(), "ORDER-7788"));
        Order second = repository.save(order(existingMerchant(), "ORDER-7788"));

        assertThat(first.orderId()).isNotEqualTo(second.orderId());
        assertThat(second.merchantOrderReference()).isEqualTo("ORDER-7788");
    }

    /**
     * NULLs are distinct under a Postgres unique index, which is why the optional reference is
     * stored as NULL rather than an empty string: any number of orders may have none.
     */
    @Test
    void allowsManyOrdersWithoutAMerchantOrderReference() {
        MerchantId merchantId = existingMerchant();

        repository.save(order(merchantId, null));
        Order second = repository.save(order(merchantId, null));

        assertThat(repository.findByOrderId(merchantId, second.orderId())).isPresent();
    }

    /**
     * Simulates the create race: the service's existence check passes, then someone else inserts the
     * same reference before the write lands. uq_orders_merchant_ref is the real guard, and it must
     * surface as the business exception rather than a raw DataIntegrityViolationException (a 500).
     */
    @Test
    void translatesTheUniqueReferenceConstraintIntoABusinessException() {
        MerchantId merchantId = existingMerchant();
        repository.save(order(merchantId, "ORDER-7788"));

        assertThatThrownBy(() -> repository.save(order(merchantId, "ORDER-7788")))
            .isInstanceOf(OrderReferenceAlreadyExistsException.class)
            .hasMessageContaining("ORDER-7788");
    }

    // --- the customer link -----------------------------------------------------

    @Test
    void savesAnOrderLinkedToACustomerOfTheSameMerchant() {
        MerchantId merchantId = existingMerchant();
        String customerId = existingCustomer(merchantId);

        Order saved = repository.save(Order.create(
            OrderId.generate(), merchantId, customerId, null, 1999, "INR", null, Map.of(), null,
            CREATED_AT
        ));

        assertThat(repository.findByOrderId(merchantId, saved.orderId()).orElseThrow().customerId())
            .isEqualTo(customerId);
    }

    /**
     * THE GUARANTEE, not the check. The application's CustomerLookup would have refused this, but
     * the composite foreign key on (merchant_id, customer_id) is what makes it impossible: even a
     * write that bypassed the service entirely cannot point an order at another tenant's customer.
     */
    @Test
    void refusesAnOrderNamingAnotherMerchantsCustomer() {
        MerchantId owner = existingMerchant();
        MerchantId outsider = existingMerchant();
        String customerOfOwner = existingCustomer(owner);

        assertThatThrownBy(() -> repository.save(Order.create(
            OrderId.generate(), outsider, customerOfOwner, null, 1999, "INR", null, Map.of(), null,
            CREATED_AT
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- updates ---------------------------------------------------------------

    @Test
    void updatesAnExistingOrderRatherThanInsertingASecondRow() {
        MerchantId merchantId = existingMerchant();
        Order saved = repository.save(order(merchantId, null));

        repository.save(saved.cancel("out of stock", CREATED_AT.plusSeconds(60)));

        Order found = repository.findByOrderId(merchantId, saved.orderId()).orElseThrow();

        assertThat(found.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(found.cancellationReason()).isEqualTo("out of stock");
        assertThat(found.version()).as("optimistic-lock counter moves on update")
            .isGreaterThan(saved.version());
    }

    // --- paging ----------------------------------------------------------------

    @Test
    void pagesNewestFirst() {
        MerchantId merchantId = existingMerchant();
        Order older = repository.save(order(merchantId, null, CREATED_AT.minusSeconds(60)));
        Order newer = repository.save(order(merchantId, null, CREATED_AT));

        List<Order> page = repository.findPage(merchantId, null, OrderCursor.start(), 10);

        assertThat(page).extracting(Order::orderId).containsExactly(newer.orderId(), older.orderId());
    }

    @Test
    void excludesOtherMerchantsOrdersFromAPage() {
        MerchantId merchantId = existingMerchant();
        repository.save(order(merchantId, null));
        repository.save(order(existingMerchant(), null));

        assertThat(repository.findPage(merchantId, null, OrderCursor.start(), 10)).hasSize(1);
    }

    @Test
    void filtersAPageByStatus() {
        MerchantId merchantId = existingMerchant();
        repository.save(order(merchantId, null, CREATED_AT));
        Order cancelled = repository.save(
            repository.save(order(merchantId, null, CREATED_AT.minusSeconds(60)))
                .cancel(null, CREATED_AT)
        );

        List<Order> page = repository.findPage(merchantId, OrderStatus.CANCELLED, OrderCursor.start(), 10);

        assertThat(page).extracting(Order::orderId).containsExactly(cancelled.orderId());
    }

    /**
     * THE BOUNDARY THE TIEBREAK EXISTS FOR, against the real SQL.
     * <p>
     * Three orders stamped with the SAME created_at -- which is exactly why the application supplies
     * time from an injectable Clock rather than letting the database default it -- paged two at a
     * time, so the page boundary falls between rows a timestamp alone cannot order.
     * <p>
     * Without "order_id" in both the ORDER BY and the cursor predicate this fails, and it fails
     * silently: a strict {@code created_at <} walks past every row sharing the boundary instant and
     * the third order is never returned, while {@code <=} returns the first two a second time.
     * Counting what came back across all pages is the only thing that notices.
     */
    @Test
    void pagesAcrossABoundaryWhereOrdersShareACreatedAt() {
        MerchantId merchantId = existingMerchant();
        Set<OrderId> created = new HashSet<>();

        for (int index = 0; index < 3; index++) {
            created.add(repository.save(order(merchantId, null, CREATED_AT)).orderId());
        }

        List<OrderId> seen = new ArrayList<>();
        OrderCursor cursor = OrderCursor.start();

        while (true) {
            List<Order> page = repository.findPage(merchantId, null, cursor, 2);

            if (page.isEmpty()) {
                break;
            }

            page.forEach(order -> seen.add(order.orderId()));
            Order last = page.get(page.size() - 1);
            cursor = OrderCursor.of(last.createdAt(), last.orderId().value());
        }

        assertThat(seen).as("every order exactly once, none skipped, none repeated")
            .hasSize(3)
            .containsExactlyInAnyOrderElementsOf(created);
    }

    // --- helpers ---------------------------------------------------------------

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            Instant.now()
        )).merchantId();
    }

    private String existingCustomer(MerchantId merchantId) {
        return customers.save(Customer.create(
            CustomerId.generate(),
            merchantId,
            null,
            UUID.randomUUID() + "@buyer.test",
            "Asha Rao",
            null,
            CREATED_AT
        )).customerId().value();
    }

    private static Order order(MerchantId merchantId, String merchantOrderReference) {
        return order(merchantId, merchantOrderReference, CREATED_AT);
    }

    private static Order order(
        MerchantId merchantId,
        String merchantOrderReference,
        Instant createdAt
    ) {
        return Order.create(
            OrderId.generate(),
            merchantId,
            null,
            merchantOrderReference,
            1999,
            "INR",
            null,
            Map.of(),
            null,
            createdAt
        );
    }
}
