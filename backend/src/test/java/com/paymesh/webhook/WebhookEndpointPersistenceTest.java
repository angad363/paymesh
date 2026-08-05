package com.paymesh.webhook;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.infrastructure.persistence.jpa.SpringDataWebhookEndpointRepository;
import com.paymesh.webhook.infrastructure.persistence.jpa.WebhookEndpointJpaEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PROVING THE ONE MAPPING THIS CAPABILITY GUESSED AT.
 *
 * <p>Five entities in this repo map {@code @JdbcTypeCode(SqlTypes.JSON)} and every one maps a
 * {@code Map}. {@code subscriptions} is the first {@code List}, and an un-annotated
 * {@code List<String>} defaults to a SQL <b>array</b> rather than jsonb -- the annotation is an
 * override nothing here had exercised. A mapping that fails {@code ddl-auto=validate} fails at
 * context startup across every integration test at once, so it is proved here, deliberately, before
 * anything is built on top of it.
 *
 * <p>The rest of this class tests the constraints V24 and V25 carry, by going through JDBC where
 * the point is that the database refuses something the application also refuses. A constraint that
 * is only ever exercised through the application is a constraint nobody has checked.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class WebhookEndpointPersistenceTest {

    @Autowired
    private SpringDataWebhookEndpointRepository endpoints;

    @Autowired
    private RegisterMerchantService merchants;

    @Autowired
    private JdbcClient jdbc;

    private String merchant() {
        return merchants.register(new RegisterMerchantCommand(
            "Hook Co", "hook-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId().value();
    }

    private WebhookEndpointJpaEntity entity(String merchantId, String url, List<String> subs) {
        Instant now = Instant.parse("2026-08-05T10:00:00Z");

        return new WebhookEndpointJpaEntity(
            EndpointId.generate().value(), merchantId, url, 1, null, null,
            subs, "ACTIVE", 0, 0L, now, now
        );
    }

    /** The whole reason this class exists. */
    @Test
    void roundTripsAListOfSubscriptionsThroughAJsonbColumn() {
        String merchantId = merchant();

        WebhookEndpointJpaEntity saved = endpoints.saveAndFlush(entity(
            merchantId, "https://merchant.test/" + UUID.randomUUID(),
            List.of("payment.succeeded", "refund.succeeded")
        ));

        endpoints.flush();

        WebhookEndpointJpaEntity read = endpoints.findById(saved.getEndpointId()).orElseThrow();

        assertThat(read.getSubscriptions())
            .containsExactly("payment.succeeded", "refund.succeeded");
    }

    /** And that it really is jsonb, not an array or a serialized blob PostgreSQL cannot read. */
    @Test
    void storesSubscriptionsAsJsonPostgresCanItselfQuery() {
        String merchantId = merchant();

        WebhookEndpointJpaEntity saved = endpoints.saveAndFlush(entity(
            merchantId, "https://merchant.test/" + UUID.randomUUID(), List.of("order.paid")
        ));

        endpoints.flush();

        String firstElement = jdbc
            .sql("select subscriptions->>0 from webhook_endpoints where endpoint_id = ?")
            .param(saved.getEndpointId())
            .query(String.class)
            .single();

        assertThat(firstElement).isEqualTo("order.paid");
    }

    // --- the constraints ------------------------------------------------------------------------

    /**
     * A PLAINTEXT WEBHOOK URL LEAKS THE PAYLOAD, so the database refuses it even though the
     * aggregate does too. Inserted through JDBC to prove the constraint, not the Java.
     */
    @Test
    void theDatabaseRefusesAPlaintextHttpUrl() {
        String merchantId = merchant();

        assertThatThrownBy(() -> insertRaw(merchantId, "http://merchant.test/hooks", "ACTIVE"))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("ck_webhook_endpoints_url_https");
    }

    /** Case-insensitive on purpose: HTTPS:// is a valid URL and LIKE 'https://%' would reject it. */
    @Test
    void theDatabaseAcceptsAnUppercaseHttpsScheme() {
        String merchantId = merchant();

        insertRaw(merchantId, "HTTPS://merchant.test/" + UUID.randomUUID(), "ACTIVE");
    }

    @Test
    void theDatabaseRefusesAStatusOutsideTheEnum() {
        String merchantId = merchant();

        assertThatThrownBy(() -> insertRaw(
            merchantId, "https://merchant.test/" + UUID.randomUUID(), "PAUSED"
        )).isInstanceOf(Exception.class).hasMessageContaining("ck_webhook_endpoints_status");
    }

    /** Two endpoints at one URL would fan out twice to the same place. */
    @Test
    void theDatabaseRefusesASecondEndpointAtTheSameUrlForOneMerchant() {
        String merchantId = merchant();
        String url = "https://merchant.test/" + UUID.randomUUID();

        endpoints.saveAndFlush(entity(merchantId, url, List.of("payment.succeeded")));

        assertThatThrownBy(() -> {
            endpoints.saveAndFlush(entity(merchantId, url, List.of("payment.succeeded")));
            endpoints.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The same URL at a DIFFERENT merchant is fine -- the unique is per tenant, not global. */
    @Test
    void twoMerchantsMayShareAUrl() {
        String url = "https://shared.test/" + UUID.randomUUID();

        endpoints.saveAndFlush(entity(merchant(), url, List.of("payment.succeeded")));
        endpoints.saveAndFlush(entity(merchant(), url, List.of("payment.succeeded")));

        endpoints.flush();
    }

    /**
     * The rotation columns are meaningful only together: a previous version with no expiry would
     * sign forever, and an expiry with no version would be inert.
     */
    @Test
    void theDatabaseRefusesHalfARotationWindow() {
        String merchantId = merchant();

        assertThatThrownBy(() -> jdbc.sql("""
                insert into webhook_endpoints
                    (endpoint_id, merchant_id, url, secret_version, previous_secret_version,
                     previous_secret_expires_at, subscriptions, status, consecutive_failures,
                     version, created_at, updated_at)
                values (?, ?, ?, 2, 1, null, '["payment.succeeded"]'::jsonb, 'ACTIVE', 0, 0,
                        now(), now())
                """)
            .params(EndpointId.generate().value(), merchantId,
                "https://merchant.test/" + UUID.randomUUID())
            .update()
        ).isInstanceOf(Exception.class)
            .hasMessageContaining("ck_webhook_endpoints_rotation_window");
    }

    private void insertRaw(String merchantId, String url, String status) {
        jdbc.sql("""
                insert into webhook_endpoints
                    (endpoint_id, merchant_id, url, secret_version, subscriptions, status,
                     consecutive_failures, version, created_at, updated_at)
                values (?, ?, ?, 1, '["payment.succeeded"]'::jsonb, ?, 0, 0, now(), now())
                """)
            .params(EndpointId.generate().value(), merchantId, url, status)
            .update();
    }
}
