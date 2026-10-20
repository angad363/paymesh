package com.paymesh;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-048 section 1 and the spec's "never" list: the payment service posts money by emitting
 * {@code payment.succeeded} / {@code refund.succeeded}, and the ledger posts from them. It takes no
 * ledger or settlement dependency, so there is no synchronous ledger call to make. A source scan, because that is the only form in which "no such import exists"
 * fails the moment someone adds one.
 */
class PaymentServiceBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void mainCodeNeverNamesTheLedgerOrSettlement() throws IOException {
        List<String> offenders;

        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                .filter(PaymentServiceBoundaryTest::namesLedgerOrSettlement)
                .map(Path::toString)
                .toList();
        }

        assertThat(offenders).isEmpty();
    }

    private static boolean namesLedgerOrSettlement(Path path) {
        String source = read(path);
        return source.contains("com.paymesh.ledger") || source.contains("com.paymesh.settlement");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
