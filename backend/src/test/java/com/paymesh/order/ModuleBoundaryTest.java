package com.paymesh.order;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-008, enforced rather than remembered.
 * <p>
 * The CustomerLookup port only buys anything if the rest of the module actually goes through it. A
 * single convenient {@code import com.paymesh.customer.application.GetCustomerService} somewhere in
 * the application layer would quietly undo the boundary, and nothing else in the build would notice
 * until Customer had to be extracted.
 * <p>
 * Reading the source text is crude next to ArchUnit, which is the right tool -- but adding a
 * dependency to assert one rule is a worse trade than fifteen lines. Swap it out when the second
 * architecture rule shows up.
 */
class ModuleBoundaryTest {

    private static final Path ORDER_SOURCES = Path.of("src/main/java/com/paymesh/order");

    /**
     * The adapter, which does the delegating, and the configuration, which constructs it. Wiring an
     * adapter cannot avoid naming the thing it adapts; what matters is that both live in
     * {@code infrastructure}, so nothing in api, application or domain can see Customer at all.
     */
    private static final List<String> ALLOWED = List.of(
        "CustomerModuleLookup.java",
        "OrderConfiguration.java"
    );

    @Test
    void reachesTheCustomerModuleOnlyThroughTheSingleAdapter() throws IOException {
        try (Stream<Path> sources = Files.walk(ORDER_SOURCES)) {
            List<Path> offenders = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !ALLOWED.contains(path.getFileName().toString()))
                .filter(ModuleBoundaryTest::importsCustomer)
                .toList();

            assertThat(offenders)
                .as("only %s may import com.paymesh.customer; the rest of the module "
                    + "depends on the CustomerLookup port (ADR-008)", ALLOWED)
                .isEmpty();
        }
    }

    private static boolean importsCustomer(Path source) {
        try {
            return Files.readAllLines(source).stream()
                .anyMatch(line -> line.startsWith("import com.paymesh.customer."));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + source, exception);
        }
    }
}
