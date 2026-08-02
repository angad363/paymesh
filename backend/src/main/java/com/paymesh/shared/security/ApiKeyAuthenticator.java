package com.paymesh.shared.security;

import java.util.Optional;

/**
 * Verifies a presented API key. A PORT DECLARED IN {@code shared}, IMPLEMENTED BY {@code merchant}.
 *
 * <h2>WHY THE PORT EXISTS AT ALL</h2>
 *
 * The filter has to run <b>inside</b> the Spring Security chain -- the chain ends with
 * {@code .anyRequest().authenticated()}, so anything registered after it is refused before it can
 * authenticate anybody. The chain is built in {@code SecurityConfiguration}, which lives in
 * {@code shared}, and {@code shared} may not import a capability.
 * <p>
 * So the filter lives here and the credential store answers through this interface, exactly as
 * {@code MerchantStatusGate} does for merchant status (ADR-021). {@code ModuleBoundaryTest} keeps
 * {@code shared} free of capability imports either way.
 */
public interface ApiKeyAuthenticator {

    /**
     * @param presented the raw value after the {@code ApiKey } scheme, i.e.
     *     {@code ak_<prefix>.<secret>}
     * @return empty when the key is unknown, malformed, revoked, or the secret does not match.
     *     ALL FOUR ARE ONE ANSWER, deliberately: distinguishing them would confirm which prefixes
     *     exist, and a revoked key answering differently from an unknown one tells an attacker they
     *     once had something real.
     */
    Optional<ApiKeyIdentity> authenticate(String presented);
}
