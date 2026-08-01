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

    @Test
    void paymentReachesTheOrderModuleOnlyThroughItsSingleAdapter() throws IOException {
        assertOnlyTheseImport(
            "com/paymesh/payment",
            "com.paymesh.order.",
            List.of(
                "payment/infrastructure/order/OrderModuleLookup.java",
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
     */
    @Test
    void orderNeverImportsPayment() throws IOException {
        assertOnlyTheseImport("com/paymesh/order", "com.paymesh.payment.", List.of());
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
