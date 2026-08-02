package com.paymesh.ledger.infrastructure.config;

import com.paymesh.ledger.application.BalanceRepository;
import com.paymesh.ledger.application.GetBalancesService;
import com.paymesh.ledger.application.LedgerAccountRepository;
import com.paymesh.ledger.application.LedgerTransactionRepository;
import com.paymesh.ledger.application.PostPaymentCapturedService;
import com.paymesh.ledger.infrastructure.events.PaymentSucceededLedgerHandler;
import com.paymesh.ledger.infrastructure.persistence.jpa.JpaBalanceRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.JpaLedgerAccountRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.JpaLedgerTransactionRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.SpringDataLedgerAccountRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.SpringDataLedgerEntryRepository;
import com.paymesh.ledger.infrastructure.persistence.jpa.SpringDataLedgerTransactionRepository;
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
 */
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
    BalanceRepository balanceRepository(SpringDataLedgerEntryRepository entries) {
        return new JpaBalanceRepository(entries);
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
}
