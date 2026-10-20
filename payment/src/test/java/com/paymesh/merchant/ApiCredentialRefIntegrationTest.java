package com.paymesh.merchant;

import com.paymesh.TestMerchants;
import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.ApiCredentialSecrets;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-048 section 4, option 3: ApiKey verification in the payment service runs against the local
 * {@code api_credential_ref} projection, fed by the merchant service's
 * {@code merchant.api_credential.issued/revoked} events. The events below carry exactly the payload
 * {@code IssueApiCredentialService} appends (the merchant service's own tests pin that payload).
 * <p>
 * Not {@code @Transactional}: the dispatcher opens its own transaction per (handler, event), and a
 * wrapping test transaction would hide whether the inbox row and the projection commit together.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class ApiCredentialRefIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventDispatcher dispatcher;

    @Autowired
    private TestMerchants testMerchants;

    @Autowired
    private JdbcTemplate jdbc;

    /** Issue (in "merchant"), call payment, 201: the whole point of the projection. */
    @Test
    void anApiKeyIssuedByTheMerchantServiceCreatesAnOrderHere() throws Exception {
        MerchantId merchantId = testMerchants.active();
        Key key = Key.issuedFor(merchantId);

        dispatcher.dispatch(key.issuedEvent());

        mockMvc.perform(newOrder(key))
            .andExpect(status().isCreated());
    }

    @Test
    void anUnknownKeyIsRefused() throws Exception {
        Key key = Key.issuedFor(testMerchants.active());

        mockMvc.perform(newOrder(key)).andExpect(status().isUnauthorized());
    }

    @Test
    void aWrongSecretOnAKnownPrefixIsRefused() throws Exception {
        Key key = Key.issuedFor(testMerchants.active());
        dispatcher.dispatch(key.issuedEvent());

        mockMvc.perform(newOrder(key.withSecret("not-the-secret"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedKeyIsRefused() throws Exception {
        Key key = Key.issuedFor(testMerchants.active());
        dispatcher.dispatch(key.issuedEvent());
        mockMvc.perform(newOrder(key)).andExpect(status().isCreated());

        dispatcher.dispatch(key.revokedEvent());

        mockMvc.perform(newOrder(key)).andExpect(status().isUnauthorized());
    }

    /** At-least-once delivery: the same event twice changes nothing, and leaves one inbox row. */
    @Test
    void redeliveringAnEventIsANoOp() {
        Key key = Key.issuedFor(testMerchants.active());
        OutboxEvent issued = key.issuedEvent();

        dispatcher.dispatch(issued);
        dispatcher.dispatch(issued);

        assertThat(refRows(key)).isEqualTo(1);
        assertThat(inboxRows(issued)).isEqualTo(1);

        OutboxEvent revoked = key.revokedEvent();
        dispatcher.dispatch(revoked);
        dispatcher.dispatch(revoked);

        assertThat(refRows(key)).isEqualTo(1);
        assertThat(inboxRows(revoked)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select revoked_at is not null from api_credential_ref where api_credential_id = ?",
            Boolean.class, key.apiCredentialId
        )).isTrue();
    }

    /** Events can overtake each other: revoked-then-issued must still end revoked, and a replayed issued must not un-revoke. */
    @Test
    void revocationIsNeverUndoneByALateOrReplayedIssue() throws Exception {
        Key key = Key.issuedFor(testMerchants.active());

        dispatcher.dispatch(key.revokedEvent());
        dispatcher.dispatch(key.issuedEvent());
        dispatcher.dispatch(key.issuedEvent());

        mockMvc.perform(newOrder(key)).andExpect(status().isUnauthorized());
    }

    private long refRows(Key key) {
        return jdbc.queryForObject(
            "select count(*) from api_credential_ref where api_credential_id = ?",
            Long.class, key.apiCredentialId
        );
    }

    private long inboxRows(OutboxEvent event) {
        return jdbc.queryForObject(
            "select count(*) from processed_events where consumer_name = 'payment-api-credential' and event_id = ?",
            Long.class, event.eventId().value()
        );
    }

    private static MockHttpServletRequestBuilder newOrder(Key key) {
        return post("/api/v1/orders")
            .header("Authorization", "ApiKey " + key.presented())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amountMinor\": 1999, \"currency\": \"INR\"}");
    }

    /** One credential as the merchant service would have issued it. */
    private record Key(
        String apiCredentialId, MerchantId merchantId, String publicPrefix, String secret
    ) {

        static Key issuedFor(MerchantId merchantId) {
            return new Key(
                "apc_" + UUID.randomUUID(), merchantId,
                "ak_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                "secret-" + UUID.randomUUID()
            );
        }

        Key withSecret(String other) {
            return new Key(apiCredentialId, merchantId, publicPrefix, other);
        }

        String presented() {
            return publicPrefix + "." + secret;
        }

        OutboxEvent issuedEvent() {
            return event("merchant.api_credential.issued", null);
        }

        OutboxEvent revokedEvent() {
            return event("merchant.api_credential.revoked", Instant.now());
        }

        private OutboxEvent event(String type, Instant revokedAt) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("apiCredentialId", apiCredentialId);
            payload.put("merchantId", merchantId.value());
            payload.put("publicPrefix", publicPrefix);
            payload.put("secretHash", ApiCredentialSecrets.hash(secret));
            payload.put("role", "MERCHANT_ADMIN");
            payload.put("revokedAt", revokedAt == null ? null : revokedAt.toString());
            payload.put("eventVersion", 1);

            // Fresh event id per CALL: a redelivery test reuses the OutboxEvent instance itself.
            return new OutboxEvent(
                EventId.generate(), merchantId, "MERCHANT", merchantId.value(), type, 1, payload, Instant.now()
            );
        }
    }
}
