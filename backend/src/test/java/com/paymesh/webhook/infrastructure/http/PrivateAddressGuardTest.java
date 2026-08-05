package com.paymesh.webhook.infrastructure.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSRF guard, checked against literal addresses so the test needs no DNS.
 *
 * <p>{@code InetAddress.getAllByName} on an IP literal parses it rather than resolving it, so every
 * case below is offline and deterministic. Hostname resolution is the same code path with one more
 * step in front of it.
 */
class PrivateAddressGuardTest {

    private final PrivateAddressGuard guard = new PrivateAddressGuard(false);

    @Test
    void allowsAPublicAddress() {
        assertThat(guard.rejectionFor("93.184.216.34")).isNull();
    }

    @Test
    void refusesLoopback() {
        assertThat(guard.rejectionFor("127.0.0.1")).isNotNull();
        assertThat(guard.rejectionFor("[::1]")).isNotNull();
    }

    /** THE INSTANCE METADATA SERVICE, which is the whole reason this class exists. */
    @Test
    void refusesTheLinkLocalMetadataAddress() {
        assertThat(guard.rejectionFor("169.254.169.254")).isNotNull();
    }

    @Test
    void refusesThePrivateRfc1918Ranges() {
        assertThat(guard.rejectionFor("10.0.0.5")).isNotNull();
        assertThat(guard.rejectionFor("172.16.4.9")).isNotNull();
        assertThat(guard.rejectionFor("192.168.1.1")).isNotNull();
    }

    @Test
    void refusesTheWildcardAddress() {
        assertThat(guard.rejectionFor("0.0.0.0")).isNotNull();
    }

    /** Neither of these is covered by {@code isSiteLocalAddress}, which is why they are named. */
    @Test
    void refusesCarrierGradeNatAndIpv6UniqueLocal() {
        assertThat(guard.rejectionFor("100.64.0.1")).isNotNull();
        assertThat(guard.rejectionFor("100.127.255.254")).isNotNull();
        assertThat(guard.rejectionFor("[fd00::1]")).isNotNull();
        assertThat(guard.rejectionFor("[fc00::1]")).isNotNull();
    }

    /** 100.63 and 100.128 are ordinary public space; the mask must not over-reach. */
    @Test
    void allowsTheAddressesEitherSideOfTheCarrierGradeNatBlock() {
        assertThat(guard.rejectionFor("100.63.255.255")).isNull();
        assertThat(guard.rejectionFor("100.128.0.1")).isNull();
    }

    @Test
    void refusesAHostThatDoesNotResolve() {
        assertThat(guard.rejectionFor("no-such-host.invalid")).isNotNull();
    }

    @Test
    void refusesAMissingHost() {
        assertThat(guard.rejectionFor(null)).isNotNull();
        assertThat(guard.rejectionFor("  ")).isNotNull();
    }

    /** The development switch, which {@code WebhookConfiguration} only honours under dev alone. */
    @Test
    void allowsEverythingWhenPrivateAddressesAreExplicitlyPermitted() {
        assertThat(new PrivateAddressGuard(true).rejectionFor("127.0.0.1")).isNull();
    }
}
