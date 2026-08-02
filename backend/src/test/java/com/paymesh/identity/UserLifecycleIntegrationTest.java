package com.paymesh.identity;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.identity.application.IssuedTokens;
import com.paymesh.identity.application.LoginCommand;
import com.paymesh.identity.application.AuthenticationService;
import com.paymesh.identity.application.RegisterUserCommand;
import com.paymesh.identity.application.RegisterUserService;
import com.paymesh.identity.domain.UserId;
import com.paymesh.merchant.application.ChangeMerchantStatusService;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE LAST FROZEN LIFECYCLE ENUM, AND THE QUESTION ADR-023 DEFERRED.
 * <p>
 * {@code UserStatus} has had three values since V2 and only ACTIVE was ever produced. ADR-021 added
 * the aggregate methods and claimed the state was reachable; nothing called them, and ADR-023
 * recorded that as still outstanding rather than pretending otherwise.
 * <p>
 * The question was "who may disable a user". The answer is that it was two questions -- see
 * ADR-024 -- and the tests that matter most here are the two that prove the scopes do not leak into
 * each other.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class UserLifecycleIntegrationTest {

    private static final String PASSWORD = "Sup3rSecret!pass";
    private static final String PLATFORM = "usr_00000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisterMerchantService merchants;

    @Autowired
    private ChangeMerchantStatusService changeMerchantStatus;

    @Autowired
    private RegisterUserService registerUser;

    @Autowired
    private AuthenticationService authentication;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    // --- platform scope: barring the human ------------------------------------------------------

    /**
     * A DEPARTED EMPLOYEE'S ACCOUNT CAN FINALLY BE DISABLED.
     * <p>
     * <b>This is a whole-path assertion and NOT a test of session revocation</b>, which is worth
     * being precise about. TWO mechanisms independently stop the refresh: the tokens being revoked,
     * and the refresh path re-reading the user and refusing an inactive one. Removing the
     * revocation leaves this green -- that was measured, not assumed. The revocation is isolated by
     * {@link #revokesEveryLiveTokenTheMomentAUserIsSuspended} below.
     */
    @Test
    void suspendingAUserStopsLoginAndKillsLiveSessions() throws Exception {
        MerchantId merchantId = activatedMerchant();
        String email = email();
        UserId userId = register(merchantId, email);

        IssuedTokens session = authentication.login(new LoginCommand(email, PASSWORD, "127.0.0.1"));

        mockMvc.perform(post("/api/v1/users/" + userId.value() + "/suspend").with(platform()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUSPENDED"));

        assertThatThrownBy(() -> authentication.login(new LoginCommand(email, PASSWORD, "127.0.0.1")))
            .as("login is refused")
            .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> authentication.refresh(session.refreshToken(), "127.0.0.1"))
            .as("and the live session cannot be refreshed either")
            .isInstanceOf(RuntimeException.class);
    }

    /**
     * THE REVOCATION ITSELF, ISOLATED, because the test above cannot see it.
     * <p>
     * Asserted on the stored token rather than on a refresh attempt: a refresh fails either way,
     * so only the row can say whether suspension actually ended the session or merely relied on the
     * status re-check still being there. If that re-check were ever removed or bypassed, a live
     * refresh token would keep minting access tokens for up to thirty days after the bar.
     * <p>
     * <b>Sabotage that must turn this red:</b> drop {@code refreshTokens.revokeAllForUser} from
     * {@code endEverySession}.
     */
    @Test
    void revokesEveryLiveTokenTheMomentAUserIsSuspended() throws Exception {
        MerchantId merchantId = activatedMerchant();
        String email = email();
        UserId userId = register(merchantId, email);

        authentication.login(new LoginCommand(email, PASSWORD, "127.0.0.1"));
        authentication.login(new LoginCommand(email, PASSWORD, "127.0.0.1"));

        assertThat(liveTokenCount(userId)).isEqualTo(2);

        mockMvc.perform(post("/api/v1/users/" + userId.value() + "/suspend").with(platform()))
            .andExpect(status().isOk());

        assertThat(liveTokenCount(userId))
            .as("every family, not just one -- a suspension is about the person")
            .isZero();
    }

    /** And it is on the security log under its own name, not as a sign-out. */
    @Test
    void recordsTheSuspensionAsItsOwnSecurityEvent() throws Exception {
        UserId userId = register(activatedMerchant(), email());

        mockMvc.perform(post("/api/v1/users/" + userId.value() + "/suspend").with(platform()))
            .andExpect(status().isOk());

        assertThat(jdbc.sql("select event_type from security_events where actor = ?")
            .param(userId.value())
            .query(String.class)
            .list())
            .contains("USER_SUSPENDED");
    }

    @Test
    void reactivatingRestoresLogin() throws Exception {
        MerchantId merchantId = activatedMerchant();
        String email = email();
        UserId userId = register(merchantId, email);

        mockMvc.perform(post("/api/v1/users/" + userId.value() + "/suspend").with(platform()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/users/" + userId.value() + "/reactivate").with(platform()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(authentication.login(new LoginCommand(email, PASSWORD, "127.0.0.1"))).isNotNull();
    }

    /** Terminal, like a merchant closure and for the same reason. */
    @Test
    void closingIsTerminal() throws Exception {
        UserId userId = register(activatedMerchant(), email());

        mockMvc.perform(post("/api/v1/users/" + userId.value() + "/close").with(platform()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(post("/api/v1/users/" + userId.value() + "/reactivate").with(platform()))
            .andExpect(status().isConflict());
    }

    /** A merchant cannot bar a human from the platform, however senior they are at their tenant. */
    @Test
    void refusesPlatformActionsToAMerchantAdmin() throws Exception {
        MerchantId merchantId = activatedMerchant();
        UserId userId = register(merchantId, email());

        mockMvc.perform(post("/api/v1/users/" + userId.value() + "/suspend").with(admin(merchantId)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
    }

    // --- merchant scope: the departed employee ---------------------------------------------------

    /**
     * THE CASE THE WHOLE DESIGN TURNS ON: revoking access at merchant A must not touch merchant B.
     * <p>
     * Disabling the account would have been the obvious reading of "disable a user" and it is
     * wrong -- an accountant serving two businesses would be locked out of both by either one.
     * <p>
     * <b>Sabotage that must turn this red:</b> make {@code revokeAccessAt} call {@code suspend}.
     */
    @Test
    void revokingAccessAtOneMerchantLeavesTheOtherIntact() throws Exception {
        MerchantId first = activatedMerchant();
        MerchantId second = activatedMerchant();

        String email = email();
        UserId userId = register(first, email);
        grantAt(second, userId);

        mockMvc.perform(delete("/api/v1/users/" + userId.value() + "/merchant-access")
                .with(admin(first)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.roles.length()").value(1))
            .andExpect(jsonPath("$.roles[0].merchantId").value(second.value()));

        assertThat(authentication.login(new LoginCommand(email, PASSWORD, "127.0.0.1")))
            .as("the account survives, because merchant A does not own this person")
            .isNotNull();
    }

    /** Losing the last role leaves an account with no tenant, which is allowed. */
    @Test
    void allowsRevokingTheLastRole() throws Exception {
        MerchantId merchantId = activatedMerchant();
        UserId userId = register(merchantId, email());

        mockMvc.perform(delete("/api/v1/users/" + userId.value() + "/merchant-access")
                .with(admin(merchantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles.length()").value(0));
    }

    /**
     * A USER AT ANOTHER MERCHANT IS A 404, IDENTICAL TO ONE THAT DOES NOT EXIST. Telling them apart
     * would let any merchant admin enumerate every user id on the platform.
     */
    @Test
    void hidesUsersOfOtherMerchantsBehindTheSame404() throws Exception {
        MerchantId owner = activatedMerchant();
        MerchantId stranger = activatedMerchant();
        UserId userId = register(owner, email());

        mockMvc.perform(delete("/api/v1/users/" + userId.value() + "/merchant-access")
                .with(admin(stranger)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/users/usr_" + UUID.randomUUID() + "/merchant-access")
                .with(admin(stranger)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    /**
     * They are the only role that can grant it back, so a merchant with one admin who revoked
     * themselves would need PayMesh to intervene.
     */
    @Test
    void refusesSelfRevocation() throws Exception {
        MerchantId merchantId = activatedMerchant();
        UserId userId = register(merchantId, email());

        mockMvc.perform(delete("/api/v1/users/" + userId.value() + "/merchant-access")
                .with(callerFor(userId.value(), "MERCHANT_ADMIN:" + merchantId.value())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CANNOT_REVOKE_OWN_ACCESS"));
    }

    @Test
    void refusesRevocationToAMerchantUser() throws Exception {
        MerchantId merchantId = activatedMerchant();
        UserId userId = register(merchantId, email());

        mockMvc.perform(delete("/api/v1/users/" + userId.value() + "/merchant-access")
                .with(callerFor(PLATFORM, "MERCHANT_USER:" + merchantId.value())))
            .andExpect(status().isForbidden());
    }

    // --- listing -----------------------------------------------------------------------------------

    @Test
    void listsOnlyTheCallersOwnStaff() throws Exception {
        MerchantId owner = activatedMerchant();
        MerchantId stranger = activatedMerchant();
        register(owner, email());
        register(owner, email());
        register(stranger, email());

        mockMvc.perform(get("/api/v1/users").with(admin(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    /** No password hash reaches an administrator: it is what an offline attack runs against. */
    @Test
    void neverExposesThePasswordHash() throws Exception {
        MerchantId merchantId = activatedMerchant();
        register(merchantId, email());

        String body = mockMvc.perform(get("/api/v1/users").with(admin(merchantId)))
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$");
    }

    private long liveTokenCount(UserId userId) {
        return jdbc.sql("""
                select count(*) from refresh_tokens
                 where user_id = ? and revoked_at is null
                """)
            .param(userId.value())
            .query(Long.class)
            .single();
    }

    /**
     * GRANTING TO AN UNKNOWN USER LEAKS EXISTENCE, where revoking deliberately does not.
     * <p>
     * Pinned rather than fixed. User ids are UUIDs so nothing can be enumerated, and the granted
     * role reaches into the granting merchant's own data rather than the user's -- but the
     * asymmetry with revoke is real and is recorded in ADR-024 and project-status. This test exists
     * so that closing it later is a deliberate change rather than an accident.
     */
    @Test
    void grantingToAnUnknownUserAnswersDifferentlyFromGrantingToARealOne() throws Exception {
        MerchantId merchantId = activatedMerchant();

        mockMvc.perform(post("/api/v1/users/usr_" + UUID.randomUUID() + "/merchant-access")
                .with(admin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{ \"role\": \"MERCHANT_USER\" }"))
            .andExpect(status().isNotFound());
    }

    /** A tenant promoting somebody to platform staff is the escalation the role model prevents. */
    @Test
    void refusesGrantingPlatformAdmin() throws Exception {
        MerchantId merchantId = activatedMerchant();
        UserId userId = register(merchantId, email());

        mockMvc.perform(post("/api/v1/users/" + userId.value() + "/merchant-access")
                .with(admin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{ \"role\": \"PLATFORM_ADMIN\" }"))
            .andExpect(status().isBadRequest());
    }

    // --- helpers -------------------------------------------------------------------------------------

    private UserId register(MerchantId merchantId, String email) {
        return registerUser.register(
            new RegisterUserCommand(email, PASSWORD, merchantId.value(), "127.0.0.1")
        ).userId();
    }

    /**
     * A second role at another merchant, through the real endpoint -- which is what makes the
     * isolation test meaningful and also exercises the grant half of the pair.
     */
    private void grantAt(MerchantId merchantId, UserId userId) throws Exception {
        mockMvc.perform(post("/api/v1/users/" + userId.value() + "/merchant-access")
                .with(admin(merchantId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{ \"role\": \"MERCHANT_USER\" }"))
            .andExpect(status().isOk());
    }

    private MerchantId activatedMerchant() {
        MerchantId merchantId = merchants.register(new RegisterMerchantCommand(
            "Staff Co", "staff-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();

        changeMerchantStatus.activate(merchantId, PLATFORM, "Activated for test");

        return merchantId;
    }

    private static String email() {
        return "person-" + UUID.randomUUID() + "@example.test";
    }

    private static RequestPostProcessor admin(MerchantId merchantId) {
        return callerFor(PLATFORM, "MERCHANT_ADMIN:" + merchantId.value());
    }

    private static RequestPostProcessor platform() {
        return callerFor(PLATFORM, "PLATFORM_ADMIN:" + MerchantId.generate().value());
    }

    private static RequestPostProcessor callerFor(String subject, String scopedRole) {
        return jwt().jwt(builder -> builder.subject(subject).claim("roles", List.of(scopedRole)));
    }
}
