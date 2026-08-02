package com.paymesh.simulator.application;

/**
 * Puts one signed callback on the wire.
 * <p>
 * An interface with one implementation, which the house rules otherwise discourage, and it earns its
 * place twice over:
 * <ul>
 *   <li>The application layer must not do HTTP ({@code java-coding-conventions.md} section 13), and
 *       the signing key, the URL and the timeouts are all infrastructure concerns.</li>
 *   <li><b>It is the seam that makes the important test possible.</b>
 *       {@code SimulatorCallbackDeliveryIntegrationTest} constructs the production dispatch service
 *       with a sender pointed at a real embedded server's port, so the callbacks this simulator
 *       signs actually cross a socket and are verified by the real
 *       {@code ProviderCallbackSignatureFilter}. Without this seam that test could only assert
 *       against a mock of the thing it exists to prove.</li>
 * </ul>
 */
@FunctionalInterface
public interface CallbackSender {

    /**
     * @param body the exact stored bytes. The implementation signs THESE and sends THESE; it must
     *             never re-serialize, because the signature covers what is on the wire
     */
    CallbackDelivery send(String body);
}
