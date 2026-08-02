package com.paymesh.shared.outbox;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.customer.application.CustomerRepository;
import com.paymesh.customer.domain.Customer;
import com.paymesh.customer.domain.CustomerId;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.CreateOrderCommand;
import com.paymesh.order.application.CreateOrderService;
import com.paymesh.order.application.CustomerLookup;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.application.OrderStateHistoryRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE POINT OF THE WHOLE OUTBOX PR, against a real PostgreSQL.
 * <p>
 * Deliberately NOT {@code @Transactional}. A test transaction would wrap both writes in an outer
 * transaction that rolls back at the end, and every assertion below would then pass whether or not
 * the service opened a transaction of its own -- which is precisely the failure this PR exists to
 * make impossible. The commits here are real, so each test scopes its queries to a merchant it
 * registered itself.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class OutboxTransactionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:15:30Z");

    @Autowired
    private CreateOrderService createOrderService;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private CustomerLookup customerLookup;

    /**
     * The state-history port, needed only to rebuild the service with a broken outbox below. The
     * timeline row joins the same transaction as the order and the event, so a sabotaged outbox must
     * take it down too -- and the assertion on order_state_history in the test proves it did.
     */
    @Autowired
    private OrderStateHistoryRepository orderStateHistory;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void writesExactlyOneUnpublishedOutboxEventWhenAnOrderIsCreated() {
        MerchantId merchantId = existingMerchant();

        Order order = createOrderService.create(command(merchantId, null, "ORDER-7788"));

        Map<String, Object> row = onlyOutboxRow(merchantId);

        assertThat(row.get("event_id").toString()).startsWith("evt_");
        assertThat(row).containsEntry("aggregate_type", "ORDER");
        assertThat(row).containsEntry("aggregate_id", order.orderId().value());
        assertThat(row).containsEntry("event_type", "order.created");
        assertThat(row).containsEntry("event_version", 1);
        assertThat(row.get("occurred_at")).isNotNull();
        assertThat(row.get("published_at"))
            .as("nothing publishes yet; a null published_at IS the status model")
            .isNull();
        assertThat(row.get("payload").toString())
            .contains("\"orderId\": \"" + order.orderId().value() + "\"")
            .contains("\"merchantId\": \"" + merchantId.value() + "\"")
            .contains("\"amountMinor\": 1999")
            .contains("\"currency\": \"INR\"")
            .contains("\"merchantOrderReference\": \"ORDER-7788\"")
            .contains("\"status\": \"PENDING\"")
            .contains("\"createdAt\"");
    }

    /**
     * THE HEADLINE. The order row is written first and the outbox append then throws, so at the
     * moment of failure an orders row genuinely exists in the session. If the two writes are not in
     * one transaction, that row commits and this test finds it.
     * <p>
     * A happy-path test cannot tell the difference: two separate transactions produce exactly the
     * same rows when nothing fails.
     */
    @Test
    void leavesNoOrderRowBehindWhenTheOutboxAppendFails() {
        MerchantId merchantId = existingMerchant();

        CreateOrderService sabotaged = new CreateOrderService(
            orders,
            orderStateHistory,
            customerLookup,
            event -> {
                throw new IllegalStateException("outbox is down");
            },
            transactionTemplate,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> sabotaged.create(command(merchantId, null, "ORDER-7788")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("outbox is down");

        assertThat(orderCount(merchantId)).as("the order must not survive its lost event").isZero();
        assertThat(outboxCount(merchantId)).isZero();
        assertThat(jdbc.sql("select count(*) from order_state_history where merchant_id = ?")
            .param(merchantId.value())
            .query(Long.class)
            .single())
            .as("and neither may its timeline row, which is in the same transaction")
            .isZero();
    }

    /**
     * The converse, proved at the layer that owns it: an outbox row that is already inserted does
     * not survive a later failure in the same transaction.
     * <p>
     * Driven through the TransactionTemplate rather than through CreateOrderService on purpose. The
     * service writes the order first, so a failing order insert would abort before {@code append}
     * ran at all and the assertion would be vacuous. Here the outbox row is really in the session
     * when the accompanying write fails.
     */
    @Test
    void leavesNoOutboxEventBehindWhenTheAccompanyingWriteFails() {
        MerchantId merchantId = existingMerchant();
        MerchantId stranger = existingMerchant();
        String customerOfStranger = existingCustomer(stranger);

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            outboxWriter.append(orderCreated(merchantId, OrderId.generate()));

            // fk_orders_customer: an order may not name another merchant's customer.
            return orders.save(Order.create(
                OrderId.generate(), merchantId, customerOfStranger, null, 1999, "INR", null,
                Map.of(), null, NOW
            ));
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(outboxCount(merchantId)).as("the event must not outlive the state change").isZero();
        assertThat(orderCount(merchantId)).isZero();
    }

    /**
     * THE APPEND-ONLY GUARANTEE: the write path issues INSERTs and never an UPDATE. This is the
     * only assertion holding {@code @Immutable} in place, and it covers the two ways the table has
     * been made to take an UPDATE.
     * <p>
     * 1. <b>The rich payload.</b> Hibernate snapshots a JSON map by serialize/deserialize, so
     * amountMinor goes in a Long and comes back an Integer; Long.equals(Integer) is false and a
     * dirty-checked row looks changed on every flush. It has to go through CreateOrderService --
     * the String-only helper below cannot reproduce it.
     * <p>
     * 2. <b>The same event_id twice.</b> The id is application-minted and there is no
     * {@code @Version}, so Spring Data merges rather than persists, and a merge onto an existing
     * row is an UPDATE that silently rewrites an event a consumer may already have read.
     * {@code @Immutable} turns it into a no-op instead, which is why the stored payload is checked
     * as well as the counter: a passing counter with a rewritten row would not be a pass.
     */
    @Test
    void neverUpdatesAnEventItHasAlreadyAppended() {
        MerchantId merchantId = existingMerchant();
        OutboxEvent event = orderCreated(merchantId, OrderId.generate());

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        createOrderService.create(command(merchantId, null, null));
        transactionTemplate.execute(status -> appendAndReturnNull(event));
        transactionTemplate.execute(status -> appendAndReturnNull(rewritten(event)));

        assertThat(statistics.getEntityUpdateCount())
            .as("an append-only table takes inserts and nothing else")
            .isZero();

        String stored = jdbc.sql("select payload::text from outbox_events where event_id = ?")
            .param(event.eventId().value())
            .query(String.class)
            .single();

        assertThat(stored).as("a re-append cannot rewrite the event already recorded")
            .doesNotContain("ord_rewritten");
    }

    /**
     * event_id is minted per event, not derived from the clock, so two orders stamped with the same
     * instant still produce two distinguishable events -- which is what a consumer dedups on.
     */
    @Test
    void mintsADistinctEventIdForTwoOrdersCreatedAtTheSameInstant() {
        MerchantId merchantId = existingMerchant();

        createOrderService.create(command(merchantId, null, null));
        createOrderService.create(command(merchantId, null, null));

        List<String> eventIds = jdbc
            .sql("select event_id from outbox_events where merchant_id = ?")
            .param(merchantId.value())
            .query(String.class)
            .list();

        assertThat(eventIds).hasSize(2).doesNotHaveDuplicates();
    }

    /**
     * Tenancy is the database's rule here, not the writer's: fk_outbox_events_merchant refuses an
     * event for a merchant that does not exist, with no application check in the path.
     */
    @Test
    void refusesAnOutboxEventNamingAMerchantThatDoesNotExist() {
        MerchantId neverRegistered = MerchantId.generate();

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
            appendAndReturnNull(orderCreated(neverRegistered, OrderId.generate()))
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(outboxCount(neverRegistered)).isZero();
    }

    // --- helpers ---------------------------------------------------------------

    private Object appendAndReturnNull(OutboxEvent event) {
        outboxWriter.append(event);
        return null;
    }

    private static OutboxEvent orderCreated(MerchantId merchantId, OrderId orderId) {
        return new OutboxEvent(
            EventId.generate(),
            merchantId,
            "ORDER",
            orderId.value(),
            "order.created",
            1,
            Map.of("orderId", orderId.value()),
            NOW
        );
    }

    /** The same event, claiming different facts. Appending it must change nothing. */
    private static OutboxEvent rewritten(OutboxEvent original) {
        return new OutboxEvent(
            original.eventId(),
            original.merchantId(),
            original.aggregateType(),
            original.aggregateId(),
            original.eventType(),
            original.eventVersion(),
            Map.of("orderId", "ord_rewritten"),
            original.occurredAt()
        );
    }

    private static CreateOrderCommand command(
        MerchantId merchantId,
        String customerId,
        String merchantOrderReference
    ) {
        return new CreateOrderCommand(
            merchantId, customerId, merchantOrderReference, 1999, "INR", null, Map.of(), null
        );
    }

    private Map<String, Object> onlyOutboxRow(MerchantId merchantId) {
        List<Map<String, Object>> rows = jdbc
            .sql("""
                select event_id, aggregate_type, aggregate_id, event_type, event_version,
                       payload::text as payload, occurred_at, published_at
                  from outbox_events
                 where merchant_id = ?
                """)
            .param(merchantId.value())
            .query()
            .listOfRows();

        assertThat(rows).as("exactly one event per order").hasSize(1);
        return rows.get(0);
    }

    private long outboxCount(MerchantId merchantId) {
        return jdbc.sql("select count(*) from outbox_events where merchant_id = ?")
            .param(merchantId.value())
            .query(Long.class)
            .single();
    }

    private long orderCount(MerchantId merchantId) {
        return jdbc.sql("select count(*) from orders where merchant_id = ?")
            .param(merchantId.value())
            .query(Long.class)
            .single();
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            NOW
        ).activate(NOW)).merchantId();
    }

    private String existingCustomer(MerchantId merchantId) {
        return customers.save(Customer.create(
            CustomerId.generate(),
            merchantId,
            null,
            UUID.randomUUID() + "@buyer.test",
            "Asha Rao",
            null,
            NOW
        )).customerId().value();
    }
}
