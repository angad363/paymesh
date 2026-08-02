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
