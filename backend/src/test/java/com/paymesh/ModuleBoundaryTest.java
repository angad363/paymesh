package com.paymesh;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-008, enforced rather than remembered.
 * <p>
 * A port only buys anything if the rest of the module actually goes through it. A single convenient
 * {@code import com.paymesh.customer.application.GetCustomerService} in Order's application layer,
 * or {@code import com.paymesh.order.application.GetOrderService} in Payment's, would quietly undo
 * the boundary, and nothing else in the build would notice until one of them had to be extracted.
 * <p>
 * It lives in {@code com.paymesh} rather than inside one module because it now makes claims about
 * two of them, and a test asserting facts about Payment should not sit in Order's package.
 * <p>
 * THE ALLOWLIST IS BY PATH, NOT BY FILENAME. It used to match on the file name alone, which meant a
 * second file called {@code OrderConfiguration.java} created anywhere -- including under
 * {@code order/application} -- would have been waved through. Matching the path suffix pins each
 * exception to the one file in the one layer that is allowed to hold it. (This closes the smaller
 * half of open item 10.)
 * <p>
 * Reading the source text is crude next to ArchUnit, which is the right tool -- but adding a
 * dependency to assert two rules is still a worse trade than thirty lines.
 */
class ModuleBoundaryTest {

    /**
     * The adapter, which does the delegating, and the configuration, which constructs it. Wiring an
     * adapter cannot avoid naming the thing it adapts; what matters is that both live in
     * {@code infrastructure}, so nothing in api, application or domain can see the other module.
     */
    @Test
    void orderReachesTheCustomerModuleOnlyThroughItsSingleAdapter() throws IOException {
        assertOnlyTheseImport(
            "com/paymesh/order",
            "com.paymesh.customer.",
            List.of(
                "order/infrastructure/customer/CustomerModuleLookup.java",
                "order/infrastructure/config/OrderConfiguration.java"
            )
        );
    }

    /**
     * TRAFFIC IN BOTH DIRECTIONS, THROUGH THE SAME PACKAGE.
     * <p>
     * {@code OrderModuleLookup} lets Payment read an order. {@code PaymentActivityAdapter} is the
     * reverse errand -- it implements a port Order declares, so Order's expiry sweeper can ask
     * whether an order is being collected against without knowing what a payment intent is
     * (ADR-014). Both are adapters, both live in {@code infrastructure}, and an adapter cannot avoid
     * naming the thing it adapts.
     * <p>
     * <b>Neither of them makes the graph cyclic</b>, and the reverse test below is what proves it:
     * Payment imports Order, Order imports nothing, and the second adapter deliberately lives on
     * this side of the boundary so that stays true.
     */
    @Test
    void paymentReachesTheOrderModuleOnlyThroughItsSingleAdapter() throws IOException {
        assertOnlyTheseImport(
            "com/paymesh/payment",
            "com.paymesh.order.",
            List.of(
                "payment/infrastructure/order/OrderModuleLookup.java",
                "payment/infrastructure/order/PaymentActivityAdapter.java",
                "payment/infrastructure/config/PaymentConfiguration.java"
            )
        );
    }

    /**
     * THE REVERSE DIRECTION, AND IT HAS NO EXCEPTIONS AT ALL.
     * <p>
     * Order must never learn that Payment exists. Payment reads Order and writes it never -- not
     * {@code orders.status}, not {@code amount_paid_minor}. Order will move those columns itself by
     * consuming {@code payment.succeeded} once a relay and a consumer exist, and the moment an
     * import appears here that plan has silently been abandoned in favour of a direct call.
     * <p>
     * <b>THE ALLOWLIST IS STILL EMPTY AFTER ADR-014, AND THAT WAS THE CONSTRAINT THE DESIGN HAD TO
     * SATISFY.</b> Order's expiry sweeper genuinely needs an answer from Payment -- it must not
     * expire an order that has a live intent -- and the obvious way to get one would have been an
     * adapter under {@code order/infrastructure/payment}, allowlisted here. That would have made the
     * dependency cyclic: neither module extractable without the other. Instead Order declares
     * {@code PaymentActivityLookup} as its own interface and Payment implements it, so the arrow
     * still points one way and this list stays empty. If a name ever appears in it, that trade has
     * been undone.
     * <p>
     * <b>STILL EMPTY AFTER ADR-016, WHICH IS THE HARDER CASE.</b> Order now CONSUMES a Payment event
     * and writes {@code orders.status} on the strength of it. The obvious way to read
     * {@code payment.succeeded} would be to import {@code PaymentIntentStatus} to compare against, or
     * a shared payload record owned by Payment; both would put Payment in Order's import graph for a
     * fact Order is being told rather than asking for. Instead the consumer reads
     * {@code Map<String, Object>} out of the envelope, exactly as a consumer in another process
     * would -- see {@link #orderConsumesPaymentEventsWithoutNamingPaymentTypes}.
     */
    @Test
    void orderNeverImportsPayment() throws IOException {
        assertOnlyTheseImport("com/paymesh/order", "com.paymesh.payment.", List.of());
    }

    /**
     * THE SAME RULE, ONE LEVEL DOWN, BECAUSE AN EMPTY ALLOWLIST ALONE WOULD ALSO PASS IF THE
     * CONSUMER WERE SIMPLY DELETED.
     * <p>
     * {@link #orderNeverImportsPayment} says "no import of {@code com.paymesh.payment}". That is
     * satisfied by a consumer that reads a Map -- and equally by no consumer at all, with Payment
     * quietly writing {@code orders.status} through a direct call instead. So this test asserts the
     * consumer is still there and still subscribed, and that it names Payment's types nowhere in its
     * source text: not as an import, which the test above covers, and not as a fully-qualified name,
     * which it does not.
     * <p>
     * If this file disappears or stops naming {@code payment.succeeded}, the event-driven design has
     * been abandoned and the build should say so rather than staying green on a rule that no longer
     * has anything to constrain.
     */
    @Test
    void orderConsumesPaymentEventsWithoutNamingPaymentTypes() throws IOException {
        Path consumer = Path.of(
            "src/main/java/com/paymesh/order/infrastructure/events/PaymentSucceededHandler.java"
        );

        assertThat(Files.exists(consumer))
            .as("Order's consumer of payment.succeeded must exist; ADR-016 is what makes"
                + " orders.status reach PAID without Payment writing the column")
            .isTrue();

        List<String> lines = Files.readAllLines(consumer);

        assertThat(String.join("\n", lines))
            .as("the consumer must still subscribe to the event")
            .contains("payment.succeeded");

        // COMMENTS ARE EXCLUDED, and they have to be: this file's javadoc explains at length why it
        // may not name com.paymesh.payment, and a check over the raw text would fail on its own
        // documentation. What must not appear is a fully-qualified reference in CODE -- the loophole
        // the import-based test above cannot see.
        List<String> offendingLines = lines.stream()
            .map(String::strip)
            .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"))
            .filter(line -> line.contains("com.paymesh.payment"))
            .toList();

        assertThat(offendingLines)
            .as("the payload is read as a Map, which is what a consumer in another process would be"
                + " handed; a fully-qualified Payment type here is the same violation as an import")
            .isEmpty();
    }

    /**
     * THE LEDGER IMPORTS NOTHING FROM PAYMENT EITHER, AND HERE THE STAKES ARE HIGHER THAN ANYWHERE
     * ELSE IN THIS FILE.
     * <p>
     * SDD 30.1 schedules the Ledger for extraction LAST and most carefully, because it is the
     * financial source of truth and the most dangerous thing to move. That makes it the module whose
     * event-reading code is most certain to end up in another process against another database --
     * so it is the module that can least afford a type shared with its producer.
     * <p>
     * The temptation is the same one Order faced and is worth naming: comparing
     * {@code previousStatus} against {@code PaymentIntentStatus}, or reading the payload through a
     * record owned by Payment. Either would be shorter and either would make the two one deployable
     * by definition.
     */
    @Test
    void ledgerNeverImportsPayment() throws IOException {
        assertOnlyTheseImport("com/paymesh/ledger", "com.paymesh.payment.", List.of());
    }

    /**
     * The Ledger may ask Settlement for a POLICY, through exactly one adapter.
     *
     * <p>This is the arrow that points OUT of the Ledger, and it is a different thing from the one
     * {@link #noCapabilityImportsTheLedger} seals. Nothing here lets an outside caller move money:
     * the release job still posts its own journal, from inside the Ledger, in response to time
     * passing. What crosses is a duration.
     *
     * <p>Allowlisted to one file so it stays that way. A second class reaching into Settlement
     * would be the beginning of the Ledger reading somebody else's state to decide a posting, which
     * is the property ADR-018 §6 protects.
     */
    @Test
    void ledgerReachesSettlementOnlyThroughItsSingleAdapter() throws IOException {
        assertOnlyTheseImport(
            "com/paymesh/ledger",
            "com.paymesh.settlement.",
            List.of(
                "ledger/infrastructure/settlement/SettlementModuleHoldingPeriod.java",
                "ledger/infrastructure/config/LedgerConfiguration.java"
            )
        );
    }

    /**
     * AND THE ARROW DOES NOT POINT BACK EITHER, which is the direction that would break silently.
     * <p>
     * Nothing in PayMesh may reach into the Ledger to read a balance or post an entry. A capability
     * wanting money moved must emit an event the Ledger subscribes to, exactly as Payment does --
     * that is what keeps every posting traceable to a state change, and it is the property that
     * makes the missing {@code POST /internal/v1/ledger/transactions} an improvement rather than a
     * gap (ADR-018 section 6).
     * <p>
     * {@code shared} is checked too: a "convenience" balance lookup promoted into shared would put
     * the Ledger in everybody's import graph at once.
     */
    @Test
    void noCapabilityImportsTheLedger() throws IOException {
        for (String capability : CAPABILITIES) {
            if (capability.equals("ledger")) {
                continue;
            }

            // SETTLEMENT IS THE ONE EXCEPTION, AND IT IS A NAMED, TESTED CYCLE (ADR-032). The
            // Ledger already reads Settlement for a holding period; Settlement reads the Ledger for
            // a balance. Two modules importing each other is the thing this file otherwise forbids,
            // so the crossing is confined to one adapter and its configuration, and any third file
            // reaching into the Ledger from Settlement fails here. What crosses is a READ -- the
            // adapter cannot post, so neither module can move money in the other (ADR-018 section 6).
            List<String> allowed = capability.equals("settlement")
                ? List.of(
                    "settlement/infrastructure/ledger/LedgerModuleAvailableFunds.java",
                    "settlement/infrastructure/config/SettlementConfiguration.java"
                )
                : List.of();

            assertOnlyTheseImport("com/paymesh/" + capability, "com.paymesh.ledger.", allowed);
        }

        assertOnlyTheseImport("com/paymesh/shared", "com.paymesh.ledger.", List.of());
    }

    /**
     * The same one-level-down check {@link #orderConsumesPaymentEventsWithoutNamingPaymentTypes}
     * makes for Order: an empty allowlist is also satisfied by deleting the consumer and having
     * Payment post the journal itself, which would put the money path back inside the module that
     * moves the money.
     */
    @Test
    void ledgerConsumesPaymentEventsWithoutNamingPaymentTypes() throws IOException {
        Path consumer = Path.of(
            "src/main/java/com/paymesh/ledger/infrastructure/events/PaymentSucceededLedgerHandler.java"
        );

        assertThat(Files.exists(consumer))
            .as("the Ledger's consumer of payment.succeeded must exist; it is the only thing that"
                + " posts, so without it no balance moves at all")
            .isTrue();

        List<String> lines = Files.readAllLines(consumer);

        assertThat(String.join("\n", lines))
            .as("the consumer must still subscribe to the event")
            .contains("payment.succeeded");

        List<String> offendingLines = lines.stream()
            .map(String::strip)
            .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"))
            .filter(line -> line.contains("com.paymesh.payment"))
            .toList();

        assertThat(offendingLines)
            .as("the payload is read as a Map; a fully-qualified Payment type here is the same"
                + " violation as an import")
            .isEmpty();
    }

    /**
     * REFUND READS PAYMENT THROUGH EXACTLY ONE DIRECTORY, AND THIS IS THE ONLY NON-EMPTY ALLOWLIST
     * IN THIS FILE.
     * <p>
     * Refund genuinely has to ask Payment a question -- how much was captured, in what currency, and
     * is this payment in a state to be refunded at all -- and unlike Order it can do so with an
     * adapter without creating a cycle: nothing in PayMesh imports Refund, so the arrow points one
     * way and Refund stays a leaf. That is why this follows the {@code OrderLookup} shape rather
     * than the {@code PaymentActivityLookup} one (ADR-008).
     * <p>
     * TWO paths, and the second is the configuration, exactly as Payment's allowlist against Order
     * includes {@code PaymentConfiguration}: a manually-wired bean has to name the service it wires
     * (java-coding-conventions.md 13), so the config is where the adapter is handed its dependency.
     * <p>
     * If a THIRD path appears, the port has leaked out of its adapter and Payment's types are loose
     * inside Refund.
     */
    @Test
    void refundImportsPaymentOnlyThroughItsAdapter() throws IOException {
        assertOnlyTheseImport(
            "com/paymesh/refund",
            "com.paymesh.payment.",
            List.of(
                "refund/infrastructure/payment/PaymentModuleLookup.java",
                "refund/infrastructure/config/RefundConfiguration.java"
            )
        );
    }

    /**
     * AND NOTHING IMPORTS REFUND, WHICH IS WHAT KEEPS THE ARROW ONE-WAY.
     * <p>
     * Payment learns that a refund succeeded, and moves its own {@code refunded_amount_minor} and
     * status because of it -- but it learns it from an event, not from a call. The moment anything
     * here imports {@code com.paymesh.refund}, the adapter above becomes half of a cycle and
     * neither module can be extracted without the other.
     */
    @Test
    void noCapabilityImportsRefund() throws IOException {
        for (String capability : CAPABILITIES) {
            if (capability.equals("refund")) {
                continue;
            }

            // Reconciliation is the ONE exception, and it is checked more strictly than this loop
            // can express: reconciliationReachesPaymentAndRefundOnlyThroughItsAdapters pins it to
            // two named files. It reaches Refund to REPLAY a provider outcome through Refund's own
            // callback service, so the arrow still points one way -- Refund learns nothing about
            // reconciliation, and noCapabilityImportsReconciliation holds it to that.
            if (capability.equals("reconciliation")) {
                continue;
            }

            assertOnlyTheseImport("com/paymesh/" + capability, "com.paymesh.refund.", List.of());
        }

        assertOnlyTheseImport("com/paymesh/shared", "com.paymesh.refund.", List.of());
    }

    /**
     * THE LEDGER'S REVERSAL CONSUMER NAMES NEITHER REFUND NOR PAYMENT.
     * <p>
     * It reads {@code refund.succeeded} out of a Map, exactly as it reads {@code payment.succeeded}.
     * The Ledger is the module SDD 30.1 schedules for extraction last, so it is the one that can
     * least afford a type shared with a producer.
     */
    @Test
    void ledgerConsumesRefundEventsWithoutNamingRefundTypes() throws IOException {
        Path consumer = Path.of(
            "src/main/java/com/paymesh/ledger/infrastructure/events/RefundSucceededLedgerHandler.java"
        );

        assertThat(Files.exists(consumer))
            .as("the Ledger's reversal consumer must exist; ADR-018 promised it and V15's"
                + " immutability triggers are what make a reversal the only available correction")
            .isTrue();

        assertThat(Files.readString(consumer)).contains("refund.succeeded");

        assertThat(codeLinesNaming(consumer, "com.paymesh.refund"))
            .as("the payload is read as a Map, like every other consumer here")
            .isEmpty();
    }

    /**
     * PAYMENT NOW CONSUMES AN EVENT AS WELL AS PRODUCING THEM, and it still imports nothing new.
     * <p>
     * This is what makes {@code PARTIALLY_REFUNDED} and {@code REFUNDED} reachable -- both declared
     * in V8 and unreachable ever since. Payment moves its own column because Refund may not write
     * it, the same rule that stops Payment writing {@code orders.status}.
     */
    @Test
    void paymentConsumesRefundEventsWithoutNamingRefundTypes() throws IOException {
        Path consumer = Path.of(
            "src/main/java/com/paymesh/payment/infrastructure/events/RefundSucceededHandler.java"
        );

        assertThat(Files.exists(consumer))
            .as("Payment's consumer of refund.succeeded must exist; without it a refunded payment"
                + " never leaves SUCCEEDED and refunded_amount_minor stays zero forever")
            .isTrue();

        assertThat(Files.readString(consumer)).contains("refund.succeeded");

        assertThat(codeLinesNaming(consumer, "com.paymesh.refund"))
            .as("a fully-qualified Refund type here is the same violation as an import")
            .isEmpty();
    }

    /**
     * THE SIGNATURE FILTER IN {@code shared} NAMES NO CAPABILITY.
     * <p>
     * It moved out of {@code payment.infrastructure.provider} when Refund's callback route needed
     * the same scheme (ADR-019), and it could only move because its path and its request attribute
     * became constructor arguments. A constant naming either controller would have dragged a
     * capability into {@code shared}, where every module can see it.
     */
    @Test
    void theSharedSignatureFilterNamesNoCapability() throws IOException {
        Path filter = Path.of(
            "src/main/java/com/paymesh/shared/provider/ProviderCallbackSignatureFilter.java"
        );

        assertThat(Files.exists(filter)).isTrue();

        for (String capability : CAPABILITIES) {
            assertThat(codeLinesNaming(filter, "com.paymesh." + capability))
                .as("shared must not name %s", capability)
                .isEmpty();
        }
    }

    /**
     * Code lines -- not comments -- in {@code file} that mention {@code qualifiedPrefix}.
     * <p>
     * COMMENTS ARE EXCLUDED, and they have to be: these files explain at length why they may not
     * name another capability, and a check over raw text would fail on its own documentation.
     */
    private static List<String> codeLinesNaming(Path file, String qualifiedPrefix)
        throws IOException {
        return Files.readAllLines(file).stream()
            .map(String::strip)
            .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"))
            .filter(line -> line.contains(qualifiedPrefix))
            .toList();
    }

    /**
     * TWO CONSUMERS OF ONE EVENT MUST NOT SHARE AN INBOX NAME.
     * <p>
     * {@code processed_events} is keyed on {@code (consumer_name, event_id)}, so if Order's handler
     * and the Ledger's handler ever reported the same name, the first to run would mark the event
     * handled and the second would silently never see it. {@code EventDispatcher} refuses the
     * duplicate at construction, so the real failure would be a context that will not start -- but
     * only if the two names are compared, and nothing else compares them.
     * <p>
     * The names are string literals in each handler precisely so a refactor cannot change them. This
     * asserts they are still different literals.
     */
    @Test
    void orderAndLedgerConsumeThePaymentEventUnderDifferentInboxNames() throws IOException {
        String orderConsumer = Files.readString(Path.of(
            "src/main/java/com/paymesh/order/infrastructure/events/PaymentSucceededHandler.java"
        ));
        String ledgerConsumer = Files.readString(Path.of(
            "src/main/java/com/paymesh/ledger/infrastructure/events/PaymentSucceededLedgerHandler.java"
        ));

        assertThat(orderConsumer).contains("\"order.payment-succeeded\"");
        assertThat(ledgerConsumer).contains("\"ledger.payment-succeeded\"");

        assertThat(ledgerConsumer)
            .as("sharing Order's inbox name would starve the Ledger of every event Order handled"
                + " first, and the symptom would be 'the ledger never posts' with nothing in any log")
            .doesNotContain("\"order.payment-succeeded\"");
    }

    /**
     * THE SIMULATOR'S ALLOWLIST IS EMPTY IN BOTH DIRECTIONS, WHICH IS A STRICTER CLAIM THAN ANY
     * ABOVE.
     * <p>
     * Every other pair in this file permits an adapter, because reading another module's data
     * legitimately requires naming it. The simulator needs no such exception: SDD 13.2 says it does
     * not own PayMesh state, and its only influence is an HTTP POST of a signed body at
     * {@code /internal/v1/provider-callbacks} -- exactly what a third party's would be. If the
     * simulator were deleted, every test outside its own package would still pass.
     * <p>
     * <b>The temptation this closes is a one-liner.</b> {@code CallbackBody} restates the wire
     * contract that {@code ProviderCallbackRequest} defines, and {@code SimulatedOutcome} restates
     * {@code ProviderOutcome}'s four names. Importing either would be shorter and would delete the
     * boundary -- the two would then be one deployable by definition, contradicting SDD 13.6. The
     * duplication is the contract being PUBLISHED rather than SHARED, as it would be if the
     * simulator read an OpenAPI document; and when PayMesh changes the contract, the delivery
     * integration test goes red, which is the notification a shared type would have suppressed.
     */
    @Test
    void theSimulatorImportsNoOtherCapability() throws IOException {
        for (String capability : CAPABILITIES) {
            assertOnlyTheseImport("com/paymesh/simulator", "com.paymesh." + capability + ".", List.of());
        }
    }

    /**
     * The reverse, and the one that would break silently. Nothing in PayMesh may reach into the
     * simulator -- not a test helper promoted to main, not a shared enum, not the configuration.
     * A dependency this way round would mean the simulator could not be removed from a production
     * deployment, which is the first thing anyone would want to do with it.
     */
    @Test
    void noCapabilityImportsTheSimulator() throws IOException {
        for (String capability : CAPABILITIES) {
            assertOnlyTheseImport("com/paymesh/" + capability, "com.paymesh.simulator.", List.of());
        }

        assertOnlyTheseImport("com/paymesh/shared", "com.paymesh.simulator.", List.of());
    }

    /**
     * RECONCILIATION REACHES TWO CAPABILITIES, AND ONLY THROUGH ONE ADAPTER EACH.
     * <p>
     * It is the first module here that legitimately needs both Payment and Refund, because a
     * provider's daily file describes money going out and money coming back in one document. That
     * makes the allowlist the load-bearing part: the job itself must not be able to see either
     * module, or the replay-do-not-diff rule that keeps the transition logic in one place would be
     * one convenient import away from being undone.
     *
     * @see com.paymesh.reconciliation.application.ReconcileProviderDayService
     */
    @Test
    void reconciliationReachesPaymentAndRefundOnlyThroughItsAdapters() throws IOException {
        assertOnlyTheseImport(
            "com/paymesh/reconciliation",
            "com.paymesh.payment.",
            List.of(
                "reconciliation/infrastructure/payment/PaymentModuleRepair.java",
                "reconciliation/infrastructure/config/ReconciliationConfiguration.java"
            )
        );

        assertOnlyTheseImport(
            "com/paymesh/reconciliation",
            "com.paymesh.refund.",
            List.of(
                "reconciliation/infrastructure/refund/RefundModuleRepair.java",
                "reconciliation/infrastructure/config/ReconciliationConfiguration.java"
            )
        );
    }

    /**
     * NOTHING REACHES BACK INTO RECONCILIATION, and the empty allowlist is the claim. It is a
     * consumer of other modules and a producer for none: no capability should ever learn that a
     * repair job exists, or a divergence would start being handled in two places.
     */
    @Test
    void noCapabilityImportsReconciliation() throws IOException {
        for (String capability : CAPABILITIES) {
            if (capability.equals("reconciliation")) {
                continue;
            }

            assertOnlyTheseImport("com/paymesh/" + capability, "com.paymesh.reconciliation.", List.of());
        }

        assertOnlyTheseImport("com/paymesh/shared", "com.paymesh.reconciliation.", List.of());
    }

    /** Every capability package except the simulator itself. */
    private static final List<String> CAPABILITIES =
        List.of(
            "merchant", "identity", "customer", "order", "payment", "ledger", "refund",
            "reconciliation", "risk", "settlement"
        );

    private static void assertOnlyTheseImport(
        String moduleDirectory,
        String forbiddenImportPrefix,
        List<String> allowedPathSuffixes
    ) throws IOException {
        Path sources = Path.of("src/main/java", moduleDirectory);

        try (Stream<Path> paths = Files.walk(sources)) {
            List<Path> offenders = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> allowedPathSuffixes.stream()
                    .noneMatch(allowed -> normalize(path).endsWith(allowed)))
                .filter(path -> imports(path, forbiddenImportPrefix))
                .toList();

            assertThat(offenders)
                .as("only %s may import %s; the rest of %s depends on the port (ADR-008)",
                    allowedPathSuffixes, forbiddenImportPrefix, moduleDirectory)
                .isEmpty();
        }
    }

    /** Separators normalized so the path comparison is not silently Unix-only. */
    private static String normalize(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static boolean imports(Path source, String importPrefix) {
        try {
            return Files.readAllLines(source).stream()
                .anyMatch(line -> line.startsWith("import " + importPrefix));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + source, exception);
        }
    }
}
