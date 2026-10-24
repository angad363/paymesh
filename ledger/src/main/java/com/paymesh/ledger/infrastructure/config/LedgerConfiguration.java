package com.paymesh.ledger.infrastructure.config;

import com.paymesh.ledger.application.BalanceRepository;
import com.paymesh.ledger.application.GetBalancesService;
import com.paymesh.ledger.application.LedgerAccountRepository;
import com.paymesh.ledger.application.LedgerTransactionRepository;
import com.paymesh.ledger.application.PostPaymentCapturedService;
import com.paymesh.ledger.application.PostRefundReversalService;
import com.paymesh.ledger.infrastructure.events.PaymentSucceededLedgerHandler;
import com.paymesh.ledger.infrastructure.events.RefundSucceededLedgerHandler;
import com.paymesh.ledger.infrastructure.persistence.jpa.JpaBalanceRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.JpaLedgerAccountRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.JpaLedgerTransactionRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.SpringDataLedgerAccountRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.SpringDataLedgerEntryRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.SpringDataLedgerTransactionRepository;
import com.paymesh.ledger.application.HoldingPeriodPolicy;
import com.paymesh.ledger.application.ReleaseAvailableFundsService;
import com.paymesh.ledger.infrastructure.security.LedgerReadKeyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The Ledger's bean wiring.
 * <p>
 * Application and domain classes carry no {@code @Service}, {@code @Component} or
 * {@code @Autowired} -- they are plain {@code final} classes instantiated here
 * (java-coding-conventions.md 13). That is what keeps {@link PostPaymentCapturedService} testable
 * as ordinary Java, with a fixed {@link Clock} and hand-written repository doubles, and no Spring
 * context at all.
 * <p>
 * {@code @EnableScheduling} lives here because this deployable has no monolith configuration to
 * carry it: without it neither the release sweeper nor the outbox relay ever fires.
 */
@org.springframework.boot.context.properties.EnableConfigurationProperties(LedgerReleaseProperties.class)
@org.springframework.scheduling.annotation.EnableScheduling
@Configuration
public class LedgerConfiguration {

    @Bean
    LedgerAccountRepository ledgerAccountRepository(SpringDataLedgerAccountRepository accounts) {
        return new JpaLedgerAccountRepository(accounts);
    }

    @Bean
    LedgerTransactionRepository ledgerTransactionRepository(
        SpringDataLedgerTransactionRepository transactions,
        SpringDataLedgerEntryRepository entries
    ) {
        return new JpaLedgerTransactionRepository(transactions, entries);
    }

    @Bean
    BalanceRepository balanceRepository(
        SpringDataLedgerEntryRepository entries, SpringDataLedgerAccountRepository accounts
    ) {
        return new JpaBalanceRepository(entries, accounts);
    }

    @Bean
    PostPaymentCapturedService postPaymentCapturedService(
        LedgerAccountRepository ledgerAccountRepository,
        LedgerTransactionRepository ledgerTransactionRepository,
        Clock clock
    ) {
        return new PostPaymentCapturedService(
            ledgerAccountRepository, ledgerTransactionRepository, clock
        );
    }

    @Bean
    GetBalancesService getBalancesService(BalanceRepository balanceRepository) {
        return new GetBalancesService(balanceRepository);
    }

    /**
     * THE LEDGER'S SUBSCRIPTION TO {@code payment.succeeded}, DECLARED ON THE LEDGER'S SIDE.
     * <p>
     * {@code EventDispatcher} takes {@code List<EventHandler>} and Spring fills it from beans like
     * this one, so the platform's wiring never names a capability and a capability's subscriptions
     * are visible in its own configuration. Deleting this bean unsubscribes the Ledger, and nothing
     * in {@code shared} needs to know -- including Order, which subscribes to the same event from
     * its own configuration and is unaffected either way.
     * <p>
     * <b>This does not put Payment in the Ledger's import graph.</b> The bean's type is the
     * Ledger's own class and the event type is a string;
     * {@code ModuleBoundaryTest.ledgerNeverImportsPayment} keeps its empty allowlist.
     */
    @Bean
    PaymentSucceededLedgerHandler paymentSucceededLedgerHandler(
        PostPaymentCapturedService postPaymentCapturedService
    ) {
        return new PaymentSucceededLedgerHandler(postPaymentCapturedService);
    }

    /**
     * The reversal posting, and THE LEDGER'S SECOND SUBSCRIPTION.
     * <p>
     * ADR-018 recorded "no reversal path exists" as a known gap and said the immutability triggers
     * were what would make a reversal the only available option when Refund arrived. It has.
     */
    @Bean
    PostRefundReversalService postRefundReversalService(
        LedgerAccountRepository ledgerAccountRepository,
        LedgerTransactionRepository ledgerTransactionRepository,
        Clock clock
    ) {
        return new PostRefundReversalService(
            ledgerAccountRepository, ledgerTransactionRepository, clock
        );
    }

    @Bean
    RefundSucceededLedgerHandler refundSucceededLedgerHandler(
        PostRefundReversalService postRefundReversalService
    ) {
        return new RefundSucceededLedgerHandler(postRefundReversalService);
    }

    /**
     * SETTLEMENT'S THREE POSTINGS, AND THE LEDGER'S THIRD, FOURTH AND FIFTH SUBSCRIPTIONS.
     * <p>
     * Declared here, on the Ledger's side, like every other subscription: deleting these beans
     * unsubscribes the Ledger and nothing in {@code shared} or in Settlement needs to know.
     */
    @Bean
    com.paymesh.ledger.application.PostSettlementJournalsService postSettlementJournalsService(
        LedgerAccountRepository ledgerAccountRepository,
        LedgerTransactionRepository ledgerTransactionRepository,
        BalanceRepository balanceRepository,
        com.paymesh.shared.outbox.application.OutboxWriter outboxWriter,
        Clock clock
    ) {
        return new com.paymesh.ledger.application.PostSettlementJournalsService(
            ledgerAccountRepository, ledgerTransactionRepository, balanceRepository, outboxWriter, clock
        );
    }

    @Bean
    com.paymesh.ledger.infrastructure.events.SettlementLedgerHandler settlementBatchCutLedgerHandler(
        com.paymesh.ledger.application.PostSettlementJournalsService journals
    ) {
        return com.paymesh.ledger.infrastructure.events.SettlementLedgerHandler.batchCut(journals);
    }

    @Bean
    com.paymesh.ledger.infrastructure.events.SettlementLedgerHandler payoutPaidLedgerHandler(
        com.paymesh.ledger.application.PostSettlementJournalsService journals
    ) {
        return com.paymesh.ledger.infrastructure.events.SettlementLedgerHandler.payoutPaid(journals);
    }

    @Bean
    com.paymesh.ledger.infrastructure.events.SettlementLedgerHandler payoutReturnedLedgerHandler(
        com.paymesh.ledger.application.PostSettlementJournalsService journals
    ) {
        return com.paymesh.ledger.infrastructure.events.SettlementLedgerHandler.payoutReturned(journals);
    }

    @Bean
    com.paymesh.ledger.infrastructure.events.SettlementConfigRefStore settlementConfigRefStore(
        org.springframework.jdbc.core.JdbcTemplate jdbc
    ) {
        return new com.paymesh.ledger.infrastructure.events.SettlementConfigRefStore(jdbc);
    }

    /** The ledger's sixth subscription: settlement's per-merchant holding period (ADR-051). */
    @Bean
    com.paymesh.ledger.infrastructure.events.SettlementConfigRefProjector settlementConfigRefProjector(
        com.paymesh.ledger.infrastructure.events.SettlementConfigRefStore store
    ) {
        return new com.paymesh.ledger.infrastructure.events.SettlementConfigRefProjector(store);
    }

    /**
     * The projected period, or the platform default AS A VALUE for a merchant with no row (ADR-051):
     * a default row is never written, so changing the default still reaches every defaulted merchant.
     */
    @Bean
    HoldingPeriodPolicy holdingPeriodPolicy(
        com.paymesh.ledger.infrastructure.events.SettlementConfigRefStore store,
        LedgerReleaseProperties properties
    ) {
        return merchantId -> store.find(merchantId).orElse(properties.defaultHoldingPeriod());
    }

    @Bean
    ReleaseAvailableFundsService releaseAvailableFundsService(
        LedgerTransactionRepository ledgerTransactions,
        LedgerAccountRepository ledgerAccounts,
        BalanceRepository balances,
        HoldingPeriodPolicy holdingPeriodPolicy,
        TransactionTemplate transactionTemplate,
        Clock clock,
        LedgerReleaseProperties properties
    ) {
        return new ReleaseAvailableFundsService(
            ledgerTransactions, ledgerAccounts, balances, holdingPeriodPolicy,
            transactionTemplate, clock, properties.batchSize()
        );
    }

    /**
     * Authentication for Settlement's read of the ledger (ADR-047). Blank key = every call 401.
     * Constructed inline so Boot cannot also auto-register it and run it twice.
     */
    @Bean
    FilterRegistrationBean<LedgerReadKeyFilter> ledgerReadKeyFilterRegistration(
        @Value("${paymesh.ledger.read-key:}") String readKey, ObjectMapper objectMapper
    ) {
        return new FilterRegistrationBean<>(new LedgerReadKeyFilter(readKey, objectMapper));
    }
}
