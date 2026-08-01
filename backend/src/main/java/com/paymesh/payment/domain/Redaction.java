package com.paymesh.payment.domain;

import java.net.URI;

/**
 * What may be written down about a payment.
 * <p>
 * PayMesh stores no raw instrument data and no raw provider payload (SDD 12.6). Two aggregates need
 * the same URL rule -- {@link PaymentAttempt} for the merchant's return URL and
 * {@link ProviderCallback} for a provider's action URL -- and one copy of it is one place for the
 * rule to be wrong.
 */
final class Redaction {

    private Redaction() {
    }

    /**
     * Keeps a URL's scheme, authority and path; drops its query string and fragment.
     * <p>
     * A return URL routinely carries a session token, a cart id or a signed callback parameter in
     * its query, and a 3DS action URL carries a challenge token. These rows are durable audit
     * records that support staff read. Origin and path preserve everything an investigation needs --
     * where the customer was sent -- and discard the part that is a credential.
     * <p>
     * A URL that cannot be parsed is dropped entirely rather than stored verbatim: an unparseable
     * string cannot be redacted, and storing what cannot be inspected is the failure mode this
     * method exists to prevent.
     */
    static String url(String value) {
        if (value == null) {
            return null;
        }

        try {
            URI parsed = URI.create(value);

            if (parsed.getScheme() == null || parsed.getHost() == null) {
                return null;
            }

            return parsed.getScheme() + "://" + parsed.getAuthority()
                + (parsed.getRawPath() == null ? "" : parsed.getRawPath());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
