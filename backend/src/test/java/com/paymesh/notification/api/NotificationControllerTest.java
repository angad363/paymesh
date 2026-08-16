package com.paymesh.notification.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.notification.application.NotificationRepository;
import com.paymesh.notification.domain.Notification;
import com.paymesh.notification.domain.NotificationId;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE HTTP SURFACE OF THE SUPPORT ENDPOINT, which the service-level tests cannot see. The routing,
 * the platform-admin gate and the error-code mapping only exist once a request travels the filter
 * chain and the {@code @RestControllerAdvice} -- and the 403 in particular is a real bug this caught:
 * there is no global advice, so a controller that calls {@code requirePlatformAdmin} must map
 * {@link com.paymesh.shared.security.InsufficientRoleException} itself or a merchant token gets a 500.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class NotificationControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    private final MockMvc mockMvc;
    private final NotificationRepository notifications;
    private final MerchantRepository merchants;

    @Autowired
    NotificationControllerTest(
        MockMvc mockMvc, NotificationRepository notifications, MerchantRepository merchants
    ) {
        this.mockMvc = mockMvc;
        this.notifications = notifications;
        this.merchants = merchants;
    }

    @Test
    void returnsTheNotificationForAPlatformAdmin() throws Exception {
        Notification seeded = seedNotification();

        mockMvc.perform(
                get("/internal/v1/notifications/{id}", seeded.id().value()).with(platformAdmin())
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(seeded.id().value()))
            .andExpect(jsonPath("$.subject").value("Payment received"))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    /** A merchant token reaches the handler and is refused -- 403, not 500 and not 404. */
    @Test
    void forbidsAMerchantToken() throws Exception {
        Notification seeded = seedNotification();

        mockMvc.perform(
                get("/internal/v1/notifications/{id}", seeded.id().value())
                    .with(merchantToken(seeded.merchantId().value()))
            )
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
    }

    @Test
    void requiresAToken() throws Exception {
        mockMvc.perform(get("/internal/v1/notifications/{id}", "nfn_" + UUID.randomUUID()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAMalformedId() throws Exception {
        mockMvc.perform(get("/internal/v1/notifications/{id}", "not_an_id").with(platformAdmin()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void answers404ForAnUnknownId() throws Exception {
        mockMvc.perform(
                get("/internal/v1/notifications/{id}", "nfn_" + UUID.randomUUID()).with(platformAdmin())
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    // --- helpers --------------------------------------------------------------------------------

    private Notification seedNotification() {
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(), "Paymesh Notification Co",
            UUID.randomUUID() + "@paymesh.test", "IN", "INR", NOW
        ).activate(NOW)).merchantId();

        Notification notification = Notification.record(
            NotificationId.generate(), merchantId, "evt_" + UUID.randomUUID(),
            "payment.succeeded", "Payment received", "Payment pi_x for 1500 INR succeeded.", NOW
        );

        notifications.saveIfAbsent(notification);

        return notification;
    }

    private static RequestPostProcessor platformAdmin() {
        return jwt().jwt(builder -> builder
            .subject("usr_" + UUID.randomUUID())
            .claim("roles", List.of("PLATFORM_ADMIN")));
    }

    private static RequestPostProcessor merchantToken(String merchantId) {
        return jwt().jwt(builder -> builder
            .subject("usr_" + UUID.randomUUID())
            .claim("roles", List.of("MERCHANT_ADMIN:" + merchantId)));
    }
}
