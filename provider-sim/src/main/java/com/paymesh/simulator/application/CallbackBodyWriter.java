package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.CallbackBody;

/**
 * Turns a callback into the exact JSON string that will be stored, signed and sent.
 * <p>
 * A port rather than a direct Jackson call because {@code java-coding-conventions.md} section 13
 * keeps JSON out of the layers above infrastructure, and because the string this produces is
 * security-relevant: it is the byte sequence the HMAC covers. Hand-building the JSON was rejected --
 * {@code callbackReference} is caller-supplied and would need escaping, and a hand-rolled escaper on
 * the money path is not a saving.
 * <p>
 * <b>Serialization happens ONCE, here, at enqueue time.</b> The result is stored in
 * {@code provider_outbound_callbacks.body} as TEXT and the dispatcher signs and posts that stored
 * string verbatim. Nothing re-serializes it later, which is the whole reason the signature can be
 * trusted to cover the bytes on the wire.
 */
public interface CallbackBodyWriter {

    String write(CallbackBody body);

    /**
     * The same guarantee for a payout callback: serialized ONCE, here, and the stored string is
     * what the dispatcher signs and posts. Two methods rather than one generic write, so the two
     * shapes stay named types rather than an Object the writer has to guess at.
     */
    String writePayout(com.paymesh.simulator.domain.PayoutCallbackBody body);
}
