package com.paymesh.webhook.infrastructure.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Refuses to POST a merchant's payload at PayMesh's own network.
 *
 * <h2>WHY A WEBHOOK URL IS AN SSRF PRIMITIVE</h2>
 *
 * A merchant supplies a URL and PayMesh's server makes the request. Point it at
 * {@code http://169.254.169.254/} and the response is the cloud instance's credentials; point it at
 * an internal admin service and the caller is inside the perimeter. HTTPS-only (V24's
 * {@code ck_webhook_endpoints_url_https}) does not help -- an attacker can serve TLS on a private
 * address -- and neither does the userinfo check the aggregate does, which is a different attack.
 * The only thing that helps is refusing to connect to an address that is not on the public internet.
 *
 * <h2>WHY THE CHECK IS HERE AND NOT AT REGISTRATION</h2>
 *
 * Registration would check a name; delivery connects to an address, and a name can resolve
 * differently at each. Checking at registration alone is checking the wrong thing.
 *
 * <h2>THE RESIDUAL RACE, WHICH IS REAL AND IS ACCEPTED (ADR-028 §7)</h2>
 *
 * This resolves the host, judges the addresses, and then hands the URL to a client that resolves it
 * <b>again</b>. A DNS record with a one-second TTL can answer publicly here and privately there.
 * Closing it means resolving once and connecting to the literal address while still presenting the
 * original SNI and Host header -- a custom {@code DnsResolver} or a pinned socket factory. That is
 * real work for a threat model where the attacker is a registered merchant of an educational
 * platform, so the window is documented rather than closed. Redirects are separately refused
 * ({@code followRedirects(NEVER)}), which removes the far easier version of the same attack.
 */
public final class PrivateAddressGuard {

    private final boolean allowPrivateAddresses;

    /**
     * @param allowPrivateAddresses true ONLY on a development machine, where the receiver is
     *     localhost. {@code WebhookConfiguration} refuses to honour it unless {@code dev} is the
     *     single active profile, for the same reason {@code DevelopmentSecretGuard} does.
     */
    public PrivateAddressGuard(boolean allowPrivateAddresses) {
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    /**
     * @return null when the host may be dialled, otherwise why not -- the string that lands in the
     *     delivery's response excerpt, so the merchant can see their URL was rejected rather than
     *     silently unreachable
     */
    public String rejectionFor(String host) {
        if (host == null || host.isBlank()) {
            return "The endpoint URL names no host";
        }

        if (allowPrivateAddresses) {
            return null;
        }

        InetAddress[] resolved;

        try {
            // EVERY address, not the first. A name with both a public and a private A record would
            // otherwise pass here and be dialled at whichever the client picks.
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException unknown) {
            return "The endpoint host " + host + " does not resolve";
        }

        for (InetAddress address : resolved) {
            if (isPrivate(address)) {
                return "The endpoint host " + host + " resolves to a non-public address";
            }
        }

        return null;
    }

    /**
     * {@code isSiteLocalAddress} covers 10/8, 172.16/12 and 192.168/16 and nothing else, so the two
     * ranges it misses are named explicitly: IPv6 unique-local {@code fc00::/7}, which is the IPv6
     * equivalent of the three above, and IPv4 {@code 100.64/10}, which is carrier-grade NAT and is
     * routinely a cloud provider's internal fabric.
     */
    private static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }

        byte[] octets = address.getAddress();

        if (octets.length == 16) {
            return (octets[0] & 0xFE) == 0xFC;
        }

        return (octets[0] & 0xFF) == 100 && (octets[1] & 0xC0) == 64;
    }
}
