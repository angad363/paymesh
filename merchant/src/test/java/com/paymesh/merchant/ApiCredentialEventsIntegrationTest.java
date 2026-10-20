package com.paymesh.merchant;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.ApiCredentialRepository;
import com.paymesh.merchant.application.ApiCredentialSecret;
import com.paymesh.merchant.application.ApiCredentialSecrets;
import com.paymesh.merchant.application.IssueApiCredentialService;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.shared.security.CallerRole;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-048 section 4, option 3: every credential change also emits
 * {@code merchant.api_credential.issued/revoked}, in the SAME transaction, because the payment
 * service verifies ApiKeys against a projection of these events. Not {@code @Transactional}: the
 * rollback test is meaningless inside a wrapping test transaction.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class ApiCredentialEventsIntegrationTest {

    @Autowired
    private RegisterMerchantService merchants;

    @Autowired
    private IssueApiCredentialService issuer;

    @Autowired
    private ApiCredentialRepository credentials;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void issuingAKeyEmitsTheIssuedEventCarryingTheHashAndNeverTheSecret() throws Exception {
        MerchantId merchantId = merchant();

        ApiCredentialSecret issued = issuer.issue(merchantId, CallerRole.MERCHANT_ADMIN, "ci key");

        List<Map<String, Object>> events = events(merchantId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("event_type", "merchant.api_credential.issued");

        String raw = events.get(0).get("payload").toString();
        JsonNode payload = new ObjectMapper().readTree(raw);
        String secret = issued.secret().substring(issued.secret().indexOf('.') + 1);

        assertThat(payload.get("apiCredentialId").asString()).isEqualTo(issued.credential().apiCredentialId().value());
        assertThat(payload.get("merchantId").asString()).isEqualTo(merchantId.value());
        assertThat(payload.get("publicPrefix").asString()).isEqualTo(issued.credential().publicPrefix());
        assertThat(payload.get("secretHash").asString()).isEqualTo(ApiCredentialSecrets.hash(secret));
        assertThat(payload.get("role").asString()).isEqualTo("MERCHANT_ADMIN");
        assertThat(payload.get("revokedAt").isNull()).isTrue();
        assertThat(payload.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(raw).as("the plaintext secret never leaves in an event").doesNotContain(secret);
    }

    @Test
    void revokingAKeyEmitsTheRevokedEventWithTheRevocationInstant() throws Exception {
        MerchantId merchantId = merchant();
        ApiCredentialSecret issued = issuer.issue(merchantId, CallerRole.MERCHANT_USER, "to revoke");

        issuer.revoke(merchantId, issued.credential().apiCredentialId());

        List<Map<String, Object>> events = events(merchantId);
        assertThat(events).extracting(event -> event.get("event_type"))
            .containsExactly("merchant.api_credential.issued", "merchant.api_credential.revoked");

        JsonNode payload = new ObjectMapper().readTree(events.get(1).get("payload").toString());
        assertThat(payload.get("apiCredentialId").asString())
            .isEqualTo(issued.credential().apiCredentialId().value());
        assertThat(payload.get("revokedAt").isNull()).isFalse();
    }

    /** THE ATOMICITY CLAIM: if the outbox append fails, the credential change does not happen. */
    @Test
    void aFailedAppendRollsBackTheIssuedCredential() {
        MerchantId merchantId = merchant();
        IssueApiCredentialService withBrokenOutbox = new IssueApiCredentialService(
            credentials,
            event -> {
                throw new IllegalStateException("outbox is down");
            },
            transactions,
            clock
        );

        assertThatThrownBy(() -> withBrokenOutbox.issue(merchantId, CallerRole.MERCHANT_ADMIN, "doomed"))
            .isInstanceOf(IllegalStateException.class);

        assertThat(credentials.findByMerchant(merchantId)).isEmpty();
        assertThat(events(merchantId)).isEmpty();
    }

    @Test
    void aFailedAppendLeavesTheKeyLiveRatherThanHalfRevoked() {
        MerchantId merchantId = merchant();
        ApiCredentialSecret issued = issuer.issue(merchantId, CallerRole.MERCHANT_ADMIN, "survivor");
        IssueApiCredentialService withBrokenOutbox = new IssueApiCredentialService(
            credentials,
            event -> {
                throw new IllegalStateException("outbox is down");
            },
            transactions,
            clock
        );

        assertThatThrownBy(() -> withBrokenOutbox.revoke(merchantId, issued.credential().apiCredentialId()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(credentials.findByMerchant(merchantId)).singleElement()
            .satisfies(credential -> assertThat(credential.isLive()).isTrue());
        assertThat(events(merchantId)).hasSize(1);
    }

    private MerchantId merchant() {
        return merchants.register(new RegisterMerchantCommand(
            "Events Co", "events-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();
    }

    private List<Map<String, Object>> events(MerchantId merchantId) {
        return jdbc.queryForList(
            "select event_type, payload::text as payload from outbox_events "
                + "where merchant_id = ? and event_type like 'merchant.api_credential.%' order by occurred_at, event_id",
            merchantId.value()
        );
    }
}
