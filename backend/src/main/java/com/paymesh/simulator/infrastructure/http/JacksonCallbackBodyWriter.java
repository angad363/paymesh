package com.paymesh.simulator.infrastructure.http;

import com.paymesh.simulator.application.CallbackBodyWriter;
import com.paymesh.simulator.domain.CallbackBody;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes a callback body to the exact string that will be stored, signed and sent.
 * <p>
 * It takes the application's own {@code ObjectMapper} rather than building one, so the {@code
 * Instant} rendering here is the same ISO-8601 rendering every other endpoint in this codebase
 * produces. A private mapper would be free to disagree, and the field it would disagree about is
 * {@code occurredAt} -- the value PayMesh's ordering guard compares. Epoch seconds there would
 * deserialize into a completely different instant and reorder events silently.
 * <p>
 * <b>Called once per callback, at enqueue time.</b> The result is stored in
 * {@code provider_outbound_callbacks.body} and everything downstream signs and posts that stored
 * string verbatim. Nothing calls this again on the way out, which is the only reason the signature
 * can be trusted to cover what is on the wire.
 */
public final class JacksonCallbackBodyWriter implements CallbackBodyWriter {

    private final ObjectMapper objectMapper;

    public JacksonCallbackBodyWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String write(CallbackBody body) {
        return objectMapper.writeValueAsString(body);
    }
}
