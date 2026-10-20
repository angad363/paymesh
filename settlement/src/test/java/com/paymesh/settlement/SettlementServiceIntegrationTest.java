package com.paymesh.settlement;

import com.paymesh.TestcontainersConfiguration;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paymesh.settlement.application.AvailableFunds;
import com.paymesh.settlement.application.CutSettlementBatchesService;
import com.paymesh.settlement.application.RevertRejectedBatchService;
import org.slf4j.LoggerFactory;
import com.paymesh.settlement.application.GetSettlementConfigService;
import com.paymesh.settlement.application.LedgerReadUnavailableException;
import com.paymesh.settlement.application.PayoutRepository;
import com.paymesh.settlement.application.SettlementBatchRepository;
import com.paymesh.settlement.domain.Payout;
import com.paymesh.settlement.domain.PayoutStatus;
import com.paymesh.settlement.domain.SettlementBatch;
import com.paymesh.settlement.domain.SettlementBatchStatus;
import com.paymesh.settlement.infrastructure.schedule.SettlementSweeper;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The settlement service end to end against real PostgreSQL and a stub ledger (a JDK HttpServer
 * standing in for the monolith's {@code /internal/v1/ledger/**}): the cut from an HTTP read, the
 * fail-closed skip (ADR-047 section 5), the option-A revert of a rejected cut (section 4), the
 * HMAC-checked payout callback, and the deferred-total / immutability triggers V1 carries.
 * <p>
 * Deliberately NOT {@code @Transactional}: the cut commits across its own transaction and the
 * triggers fire at COMMIT.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SettlementServiceIntegrationTest {

    private static final String DESTINATION = "acct_merchant_test";
    private static final String CALLBACK_SECRET = "dev-only-insecure-provider-callback-secret-change-me";
    private static final String LEDGER_KEY = "dev-only-insecure-ledger-read-key-change-me";

    /** merchantId -> {status, body}; absent means 404. */
    private static final Map<String, StubAnswer> ANSWERS = new ConcurrentHashMap<>();
    private static final List<String> CANDIDATES = new CopyOnWriteArrayList<>();
    private static final List<String> KEYS_SEEN = new CopyOnWriteArrayList<>();
    private static final HttpServer LEDGER = startStubLedger();

    private record StubAnswer(int status, String body) {
    }

    @DynamicPropertySource
    static void ledger(DynamicPropertyRegistry registry) {
        registry.add("paymesh.settlement.ledger.url", () -> "http://localhost:" + LEDGER.getAddress().getPort());
    }

    @AfterAll
    static void stopLedger() {
        LEDGER.stop(0);
    }

    @Autowired private CutSettlementBatchesService cut;
    @Autowired private SettlementSweeper sweeper;
    @Autowired private GetSettlementConfigService configs;
    @Autowired private SettlementBatchRepository batches;
    @Autowired private PayoutRepository payouts;
    @Autowired private EventDispatcher dispatcher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private MockMvc mockMvc;
    @Autowired private AvailableFunds availableFunds;
    @Autowired private RevertRejectedBatchService revert;

    @Test
    void cutsABatchFromTheLedgersContributionsAndEmitsBatchCut() {
        MerchantId merchantId = payableMerchant();
        answerWith(merchantId, contributions(Map.of("pi_" + UUID.randomUUID(), 6_000L, "pi_" + UUID.randomUUID(), 4_000L)));

        List<SettlementBatch> cutBatches = cut.cutFor(merchantId);

        assertThat(cutBatches).hasSize(1);
        SettlementBatch batch = cutBatches.get(0);
        assertThat(batch.netAmountMinor()).isEqualTo(10_000L);
        assertThat(batch.items()).hasSize(2);
        assertThat(payouts.findByBatch(batch.settlementBatchId())).isPresent();
        assertThat(outboxCount("settlement.batch_cut", batch.settlementBatchId().value())).isEqualTo(1);
        assertThat(KEYS_SEEN).as("the shared key rides every ledger read").contains(LEDGER_KEY);
        assertThat(cut.cutFor(merchantId))
            .as("the second pass nets off the batch already cut and cuts nothing")
            .isEmpty();
    }

    /** ADR-047 section 5: no read, no batch -- and one merchant's outage skips only that merchant. */
    @Test
    void aFailedLedgerReadCutsNothingForThatMerchantAndTheSweepSkipsIt() {
        MerchantId healthy = payableMerchant();
        MerchantId unreadable = payableMerchant();
        answerWith(healthy, contributions(Map.of("pi_" + UUID.randomUUID(), 5_000L)));
        ANSWERS.put(unreadable.value(), new StubAnswer(500, "{}"));
        CANDIDATES.clear();
        CANDIDATES.addAll(List.of(unreadable.value(), healthy.value()));

        assertThatThrownBy(() -> cut.cutFor(unreadable)).isInstanceOf(LedgerReadUnavailableException.class);

        sweeper.cut();

        assertThat(batchCount(unreadable)).as("a read not got means no batch").isZero();
        assertThat(batchCount(healthy)).as("the healthy merchant is still paid").isEqualTo(1);
        assertThat(outboxCountForMerchant("settlement.batch_cut", unreadable)).isZero();
    }

    @Test
    void anUnreachableOrMalformedLedgerAnswerFailsClosedToo() {
        MerchantId malformed = payableMerchant();
        ANSWERS.put(malformed.value(), new StubAnswer(200, "{\"contributions\": \"nope\"}"));
        MerchantId missing = payableMerchant();

        assertThatThrownBy(() -> cut.cutFor(malformed)).isInstanceOf(LedgerReadUnavailableException.class);
        assertThatThrownBy(() -> cut.cutFor(missing)).isInstanceOf(LedgerReadUnavailableException.class);
        assertThat(batchCount(malformed) + batchCount(missing)).isZero();
    }

    /** Option A: the ledger refused the cut; Settlement reverts it without emitting payout.returned. */
    @Test
    void aRejectedCutIsRevertedAndItsItemsAreFreedAgain() {
        MerchantId merchantId = payableMerchant();
        String payment = "pi_" + UUID.randomUUID();
        answerWith(merchantId, contributions(Map.of(payment, 8_000L)));
        SettlementBatch batch = cut.cutFor(merchantId).get(0);

        OutboxEvent rejected = rejection(merchantId, batch);
        dispatcher.dispatch(rejected);

        assertThat(batches.find(merchantId, batch.settlementBatchId()).orElseThrow().status())
            .isEqualTo(SettlementBatchStatus.RETURNED);
        assertThat(payouts.findByBatch(batch.settlementBatchId()).orElseThrow().status())
            .isEqualTo(PayoutStatus.FAILED);
        assertThat(outboxCount("payout.returned", batch.settlementBatchId().value()))
            .as("nothing was posted, so there is no in-transit to return")
            .isZero();

        dispatcher.dispatch(rejected);
        assertThat(batches.find(merchantId, batch.settlementBatchId()).orElseThrow().status())
            .as("a redelivery is a no-op")
            .isEqualTo(SettlementBatchStatus.RETURNED);

        answerWith(merchantId, contributions(Map.of(payment, 5_000L)));
        assertThat(cut.cutFor(merchantId))
            .as("the reverted batch no longer nets off the payment: the next cut is a fresh one")
            .singleElement()
            .satisfies(next -> assertThat(next.netAmountMinor()).isEqualTo(5_000L));
    }

    @Test
    void aRejectionIsNotAppliedOnceThePayoutHasLeftTheBuilding() {
        MerchantId merchantId = payableMerchant();
        answerWith(merchantId, contributions(Map.of("pi_" + UUID.randomUUID(), 3_000L)));
        SettlementBatch batch = cut.cutFor(merchantId).get(0);
        Payout payout = payouts.findByBatch(batch.settlementBatchId()).orElseThrow();
        payouts.save(payout.submitted("prov_1", Instant.now(), Duration.ofHours(1)));

        dispatcher.dispatch(rejection(merchantId, batch));

        assertThat(batches.find(merchantId, batch.settlementBatchId()).orElseThrow().status())
            .as("a SUBMITTED payout may still be paid; reverting would strand real cash")
            .isEqualTo(SettlementBatchStatus.PENDING_PAYOUT);
    }

    /**
     * ADR-047 section 8. The payout is due the moment the batch is cut, but the provider must not be
     * paid for a cut the Ledger has not posted: it could still reject it. Only {@code
     * ledger.batch_posted} makes the payout submittable, and a rejected batch's payout never is.
     */
    @Test
    void aPayoutIsNotSubmittableUntilTheLedgerHasPostedItsBatch() {
        Instant later = Instant.now().plus(Duration.ofHours(1));
        MerchantId merchantId = payableMerchant();
        answerWith(merchantId, contributions(Map.of("pi_" + UUID.randomUUID(), 3_000L)));
        SettlementBatch batch = cut.cutFor(merchantId).get(0);
        String payoutId = payouts.findByBatch(batch.settlementBatchId()).orElseThrow().payoutId().value();

        assertThat(payouts.findDue(later, 1_000)).as("cut but not posted: not submittable").doesNotContain(payoutId);

        OutboxEvent posted = posted(merchantId, batch);
        dispatcher.dispatch(posted);

        assertThat(batches.find(merchantId, batch.settlementBatchId()).orElseThrow().status())
            .isEqualTo(SettlementBatchStatus.POSTED);
        assertThat(payouts.findDue(later, 1_000)).as("posted: submittable").contains(payoutId);

        dispatcher.dispatch(posted);
        assertThat(batches.find(merchantId, batch.settlementBatchId()).orElseThrow().status())
            .as("a redelivery is a no-op").isEqualTo(SettlementBatchStatus.POSTED);

        MerchantId rejectedMerchant = payableMerchant();
        answerWith(rejectedMerchant, contributions(Map.of("pi_" + UUID.randomUUID(), 2_000L)));
        SettlementBatch rejected = cut.cutFor(rejectedMerchant).get(0);
        String rejectedPayout = payouts.findByBatch(rejected.settlementBatchId()).orElseThrow().payoutId().value();
        dispatcher.dispatch(rejection(rejectedMerchant, rejected));

        assertThat(payouts.findDue(later, 1_000)).as("a rejected batch's payout is never submitted")
            .doesNotContain(rejectedPayout);
    }

    /**
     * A SECOND delivery of {@code settlement.batch_rejected} under a NEW event id (so the inbox does
     * not absorb it) is handled silently: no ERROR, no state change.
     */
    @Test
    void aSecondRejectionForAnAlreadyRevertedBatchIsASilentNoOp() {
        MerchantId merchantId = payableMerchant();
        answerWith(merchantId, contributions(Map.of("pi_" + UUID.randomUUID(), 4_000L)));
        SettlementBatch batch = cut.cutFor(merchantId).get(0);
        transactions.executeWithoutResult(t -> revert.revert(merchantId, batch.settlementBatchId(), "first"));

        Logger logger = (Logger) LoggerFactory.getLogger(RevertRejectedBatchService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            transactions.executeWithoutResult(t -> revert.revert(merchantId, batch.settlementBatchId(), "second"));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getLevel).doesNotContain(Level.ERROR);
        assertThat(batches.find(merchantId, batch.settlementBatchId()).orElseThrow().status())
            .isEqualTo(SettlementBatchStatus.RETURNED);
        assertThat(payouts.findByBatch(batch.settlementBatchId()).orElseThrow().status())
            .isEqualTo(PayoutStatus.FAILED);
    }

    /** A malformed id from the ledger is a fail-closed skip, not an exception that aborts the tick. */
    @Test
    void aMalformedMerchantIdFromTheLedgerFailsClosedInsteadOfAbortingTheTick() {
        CANDIDATES.clear();
        CANDIDATES.add("not-a-merchant-id");

        try {
            assertThatThrownBy(() -> availableFunds.merchantsWithAnAvailableAccount())
                .isInstanceOf(LedgerReadUnavailableException.class);
            sweeper.cut();
        } finally {
            CANDIDATES.clear();
        }
    }

    @Test
    void theCallbackRouteIsHmacCheckedNotTokenChecked() throws Exception {
        MerchantId merchantId = payableMerchant();
        answerWith(merchantId, contributions(Map.of("pi_" + UUID.randomUUID(), 7_000L)));
        SettlementBatch batch = cut.cutFor(merchantId).get(0);
        Payout payout = payouts.findByBatch(batch.settlementBatchId()).orElseThrow();
        dispatcher.dispatch(posted(merchantId, batch));
        String body = "{\"eventId\":\"whv_" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-08-01T10:00:00Z\","
            + "\"payoutId\":\"" + payout.payoutId().value() + "\",\"outcome\":\"SUCCEEDED\"}";

        mockMvc.perform(post("/internal/v1/payout-callbacks/simulator")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("PROVIDER_SIGNATURE_INVALID"));

        mockMvc.perform(post("/internal/v1/payout-callbacks/simulator")
                .header("X-PayMesh-Signature", sign(body))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("APPLIED"));

        assertThat(batches.find(merchantId, batch.settlementBatchId()).orElseThrow().status())
            .isEqualTo(SettlementBatchStatus.PAID);
        assertThat(outboxCount("payout.paid", batch.settlementBatchId().value())).isEqualTo(1);
    }

    // --- V1: the triggers moved with the tables -------------------------------------------------

    @Test
    void v1AdoptedTheTablesIntoTheSettlementSchemaWithTheirTriggers() {
        List<String> tables = jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'settlement'",
            String.class);

        assertThat(tables).contains(
            "settlement_configs", "settlement_batches", "settlement_items", "payouts",
            "payout_callbacks", "outbox_events", "processed_events", "idempotency_records");
        assertThat(jdbc.queryForList(
            "select tgname from pg_trigger t join pg_class c on c.oid = t.tgrelid "
                + "join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = 'settlement' and not t.tgisinternal", String.class))
            .contains("tr_settlement_batches_total", "tr_settlement_items_total",
                "tr_settlement_batches_append_only", "tr_settlement_items_immutable");
    }

    /**
     * V3's backfill marks POSTED only a PENDING_PAYOUT batch whose cut journal exists in the Ledger's
     * schema (stubbed here: this service owns no ledger table); with no readable ledger table it is
     * a no-op. Runs the migration's own DO block.
     * <p>
     * <b>Sabotage that must turn this red:</b> drop the EXISTS clause from V3 -- the in-flight batch
     * is then POSTED.
     */
    @Test
    void theBackfillPostsOnlyBatchesTheLedgerHasAlreadyJournalled() throws Exception {
        MerchantId merchantId = payableMerchant();
        answerWith(merchantId, contributions(Map.of("pi_" + UUID.randomUUID(), 3_000L)));
        SettlementBatch journalled = cut.cutFor(merchantId).get(0);
        MerchantId otherMerchant = payableMerchant();
        answerWith(otherMerchant, contributions(Map.of("pi_" + UUID.randomUUID(), 2_000L)));
        SettlementBatch inFlight = cut.cutFor(otherMerchant).get(0);

        String migration = new String(
            new org.springframework.core.io.ClassPathResource("db/migration/V3__batch_posted_status.sql")
                .getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        String backfill = migration.substring(migration.indexOf("DO $$"));

        jdbc.execute(backfill);
        assertThat(batches.find(merchantId, journalled.settlementBatchId()).orElseThrow().status())
            .as("no readable ledger table: nothing is backfilled").isEqualTo(SettlementBatchStatus.PENDING_PAYOUT);

        jdbc.execute("create schema ledger");
        try {
            jdbc.execute("create table ledger.ledger_transactions (idempotency_key varchar(120) primary key)");
            jdbc.update("insert into ledger.ledger_transactions values (?)",
                "settlement-batch-cut:" + journalled.settlementBatchId().value());

            jdbc.execute(backfill);

            assertThat(batches.find(merchantId, journalled.settlementBatchId()).orElseThrow().status())
                .as("the Ledger journalled this cut").isEqualTo(SettlementBatchStatus.POSTED);
            assertThat(batches.find(otherMerchant, inFlight.settlementBatchId()).orElseThrow().status())
                .as("no journal yet: must wait for ledger.batch_posted")
                .isEqualTo(SettlementBatchStatus.PENDING_PAYOUT);
        } finally {
            jdbc.execute("drop schema ledger cascade");
        }
    }

    @Test
    void aBatchWhoseItemsDoNotSumToItsNetIsRefusedAtCommit() {
        String batchId = "stl_" + UUID.randomUUID();
        String merchant = MerchantId.generate().value();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("insert into settlement_batches values (?,?,'INR',1000,now(),'PENDING_PAYOUT',now(),now())",
                batchId, merchant);
            jdbc.update("insert into settlement_items values (?,?,?,'INR',?,900,now())",
                "sti_" + UUID.randomUUID(), batchId, merchant, "pi_" + UUID.randomUUID());
        })).hasMessageContaining("declares net 1000 but its items sum to 900");
    }

    @Test
    void batchesAreAppendOnlyAndItemsAreImmutable() {
        MerchantId merchantId = payableMerchant();
        answerWith(merchantId, contributions(Map.of("pi_" + UUID.randomUUID(), 2_000L)));
        String batchId = cut.cutFor(merchantId).get(0).settlementBatchId().value();

        assertThatThrownBy(() -> jdbc.update("delete from settlement_batches where settlement_batch_id = ?", batchId))
            .hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> jdbc.update(
            "update settlement_batches set net_amount_minor = 1 where settlement_batch_id = ?", batchId))
            .hasMessageContaining("immutable except for its status");
        assertThatThrownBy(() -> jdbc.update(
            "update settlement_items set amount_minor = 1 where settlement_batch_id = ?", batchId))
            .hasMessageContaining("cannot be changed or deleted");
    }

    // --- helpers --------------------------------------------------------------------------------

    private MerchantId payableMerchant() {
        MerchantId merchantId = MerchantId.generate();
        configs.set(merchantId, Duration.ZERO, DESTINATION, 1L);
        return merchantId;
    }

    private OutboxEvent rejection(MerchantId merchantId, SettlementBatch batch) {
        return new OutboxEvent(
            EventId.generate(), merchantId, "SETTLEMENT_BATCH", batch.settlementBatchId().value(),
            "settlement.batch_rejected", 1,
            Map.of("settlementBatchId", batch.settlementBatchId().value(),
                "merchantId", merchantId.value(), "reason", "test"),
            Instant.now());
    }

    private OutboxEvent posted(MerchantId merchantId, SettlementBatch batch) {
        return new OutboxEvent(
            EventId.generate(), merchantId, "SETTLEMENT_BATCH", batch.settlementBatchId().value(),
            "ledger.batch_posted", 1,
            Map.of("settlementBatchId", batch.settlementBatchId().value(),
                "merchantId", merchantId.value(), "amountMinor", batch.netAmountMinor(), "currency", "INR"),
            Instant.now());
    }

    private int batchCount(MerchantId merchantId) {
        return jdbc.queryForObject(
            "select count(*) from settlement_batches where merchant_id = ?", Integer.class, merchantId.value());
    }

    private int outboxCount(String eventType, String aggregateId) {
        return jdbc.queryForObject(
            "select count(*) from outbox_events where event_type = ? and aggregate_id = ?",
            Integer.class, eventType, aggregateId);
    }

    private int outboxCountForMerchant(String eventType, MerchantId merchantId) {
        return jdbc.queryForObject(
            "select count(*) from outbox_events where event_type = ? and merchant_id = ?",
            Integer.class, eventType, merchantId.value());
    }

    private static void answerWith(MerchantId merchantId, String body) {
        ANSWERS.put(merchantId.value(), new StubAnswer(200, body));
    }

    private static String contributions(Map<String, Long> byPayment) {
        StringBuilder json = new StringBuilder("{\"contributions\":[");
        byPayment.forEach((payment, amount) -> json
            .append(json.charAt(json.length() - 1) == '[' ? "" : ",")
            .append("{\"currency\":\"INR\",\"paymentIntentId\":\"").append(payment)
            .append("\",\"amountMinor\":").append(amount).append("}"));
        return json.append("]}").toString();
    }

    private static String sign(String body) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CALLBACK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hex = HexFormat.of().formatHex(
            mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + hex;
    }

    private static HttpServer startStubLedger() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/v1/ledger/merchants/", exchange -> {
                KEYS_SEEN.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-PayMesh-Ledger-Key")));
                String path = exchange.getRequestURI().getPath();
                String merchantId = path.split("/")[5];
                StubAnswer answer = ANSWERS.getOrDefault(merchantId, new StubAnswer(404, "{}"));
                reply(exchange, answer.status(), answer.body());
            });
            server.createContext("/internal/v1/ledger/available-merchants", exchange -> {
                StringBuilder ids = new StringBuilder();
                CANDIDATES.forEach(id -> ids.append(ids.length() == 0 ? "" : ",").append('"').append(id).append('"'));
                reply(exchange, 200, "{\"merchantIds\":[" + ids + "]}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
