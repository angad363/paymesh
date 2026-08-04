package com.paymesh.identity;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.identity.application.AuthenticationService;
import com.paymesh.identity.application.LoginCommand;
import com.paymesh.identity.application.ManageUserAccessService;
import com.paymesh.identity.application.RegisterUserCommand;
import com.paymesh.identity.application.RegisterUserService;
import com.paymesh.identity.domain.UserId;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE ROLE THAT EXISTED IN THE ENUM AND COULD NOT BE PRODUCED. ADR-027.
 *
 * <h2>What was actually broken</h2>
 *
 * {@code user_roles.merchant_id} was NOT NULL, so no endpoint could write a platform-wide grant.
 * That reads as a curiosity until you follow it: merchant activation is PLATFORM_ADMIN-only, and
 * {@code MerchantStatusFilter} refuses every merchant-scoped write until a merchant is ACTIVE. So a
 * platform with no admin cannot activate a merchant, and an unactivated merchant can do nothing at
 * all -- which is why the Postman collection had to MINT a token with the published dev signing key
 * to get past onboarding.
 *
 * <h2>The tests that matter most</h2>
 *
 * Not the happy path. {@link #refusesToStorePlatformAdminScopedToAMerchant()} and
 * {@link #refusesToStoreAMerchantRoleWithNoMerchant()} are the ones: making the column nullable
 * without {@code ck_user_roles_scope} would let a MERCHANT_ADMIN grant themselves platform
 * authority at their own tenant, and {@code requirePlatformAdmin()} used to read exactly that shape
 * as platform staff. They go to the database directly, past every application check, because the
 * constraint is the layer that has to hold when the checks above it are refactored away.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PlatformAdminIntegrationTest {

    private static final String PASSWORD = "Sup3rSecret!pass";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisterUserService registerUser;

    @Autowired
    private RegisterMerchantService merchants;

    @Autowired
    private ManageUserAccessService manageUserAccess;

    @Autowired
    private AuthenticationService authentication;

    @Autowired
    private com.paymesh.merchant.application.ChangeMerchantStatusService changeMerchantStatus;

    @Autowired
    private JdbcClient jdbc;

    // --- the database constraint ---------------------------------------------------------------

    /**
     * THE ESCALATION PATH, CLOSED AT THE LAYER THAT CANNOT BE REFACTORED AWAY.
     *
     * <p>Inserted straight through JDBC, past {@code RoleAssignment} and past
     * {@code User.grantRoleAt}, because the question this asks is what happens when those two are
     * not in the way. If this row could be written, a merchant admin who found any route to it
     * would hold authority over every other merchant on the platform.
     */
    @Test
    void refusesToStorePlatformAdminScopedToAMerchant() {
        UserId userId = registerUser();

        assertThatThrownBy(() -> insertRole(userId, "PLATFORM_ADMIN", "mrc_" + UUID.randomUUID()))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_user_roles_scope");
    }

    /** The mirror. A merchant role with no tenant would be authority over nothing, or everything. */
    @Test
    void refusesToStoreAMerchantRoleWithNoMerchant() {
        UserId userId = registerUser();

        assertThatThrownBy(() -> insertRole(userId, "MERCHANT_ADMIN", null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_user_roles_scope");
    }

    /**
     * ONE PLATFORM GRANT PER USER PER ROLE, which a plain UNIQUE could not enforce.
     *
     * <p>In SQL, NULL is not equal to NULL, so a unique index over (user_id, merchant_id, role)
     * treats two platform grants as distinct rows and admits both.
     * {@code uq_user_roles_platform_scoped} omits the merchant column entirely for exactly this
     * reason, and this is the assertion that would fail if somebody "simplified" it back.
     */
    @Test
    void refusesASecondIdenticalPlatformGrant() {
        UserId userId = registerUser();

        insertRole(userId, "PLATFORM_ADMIN", null);

        assertThatThrownBy(() -> insertRole(userId, "PLATFORM_ADMIN", null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- the endpoints --------------------------------------------------------------------------

    @Test
    void promotesAUserToPlatformStaff() throws Exception {
        UserId target = registerUser();

        mockMvc.perform(post("/api/v1/users/" + target.value() + "/platform-admin")
                .with(platformAdmin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles[?(@.role == 'PLATFORM_ADMIN')].merchantId").value((Object) null));

        assertThat(platformGrantCount(target)).isEqualTo(1);
    }

    /**
     * A MERCHANT ADMIN CANNOT PROMOTE ANYBODY, INCLUDING THEMSELVES.
     *
     * <p>The merchant is activated first so that {@code MerchantStatusFilter} lets the request
     * through and the ROLE check is what refuses it. Against an inactive merchant this would still
     * answer 403, but for the wrong reason -- and a test that passes for the wrong reason would go
     * on passing if the role check were removed.
     */
    @Test
    void refusesPromotionFromAMerchantAdmin() throws Exception {
        UserId target = registerUser();

        mockMvc.perform(post("/api/v1/users/" + target.value() + "/platform-admin")
                .with(merchantAdmin(activatedMerchant())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));

        assertThat(platformGrantCount(target)).isZero();
    }

    @Test
    void demotesAPlatformAdminWhileAnotherRemains() throws Exception {
        UserId keep = promoted();
        UserId drop = promoted();

        mockMvc.perform(delete("/api/v1/users/" + drop.value() + "/platform-admin")
                .with(platformAdmin()))
            .andExpect(status().isOk());

        assertThat(platformGrantCount(drop)).isZero();
        assertThat(platformGrantCount(keep)).isEqualTo(1);
    }

    /**
     * A PLATFORM WITH NO ADMIN CANNOT ACTIVATE ANY MERCHANT, so every merchant registered from that
     * moment on is stuck in PENDING_VERIFICATION and the only way back is a restart or a
     * hand-written UPDATE.
     *
     * <p>Checked as "would this leave zero" rather than "is the caller the target", because
     * demoting the only OTHER admin reaches the same dead end from the other side.
     */
    @Test
    void refusesToDemoteTheLastPlatformAdmin() throws Exception {
        UserId only = onlyPlatformAdmin();

        mockMvc.perform(delete("/api/v1/users/" + only.value() + "/platform-admin")
                .with(platformAdmin()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_PLATFORM_ADMIN"));

        assertThat(platformGrantCount(only)).isEqualTo(1);
    }

    @Test
    void answersNotFoundWhenTheUserHoldsNoPlatformRole() throws Exception {
        UserId keep = promoted();
        UserId target = registerUser();

        mockMvc.perform(delete("/api/v1/users/" + target.value() + "/platform-admin")
                .with(platformAdmin()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("USER_HOLDS_NO_PLATFORM_ROLE"));

        assertThat(platformGrantCount(keep)).isEqualTo(1);
    }

    // --- the bootstrap --------------------------------------------------------------------------

    /**
     * WHERE THE FIRST ONE COMES FROM. The grant endpoint requires a caller who already holds the
     * role, so on a fresh database it can never mint the first.
     */
    @Test
    void bootstrapPromotesTheConfiguredEmailWhenThePlatformHasNone() {
        clearPlatformAdmins();

        String email = email();
        UserId userId = registerUser(email);

        assertThat(manageUserAccess.bootstrapPlatformAdmin(email)).isPresent();
        assertThat(platformGrantCount(userId)).isEqualTo(1);
    }

    /**
     * SAFE TO LEAVE THE PROPERTY SET. A second boot must not re-promote somebody who was
     * deliberately demoted, which is what makes this idempotent rather than merely repeatable.
     */
    @Test
    void bootstrapDoesNothingWhenThePlatformAlreadyHasAnAdmin() {
        clearPlatformAdmins();

        String email = email();
        registerUser(email);
        manageUserAccess.bootstrapPlatformAdmin(email);

        String secondEmail = email();
        UserId second = registerUser(secondEmail);

        assertThat(manageUserAccess.bootstrapPlatformAdmin(secondEmail)).isEmpty();
        assertThat(platformGrantCount(second)).isZero();
    }

    // --- the claim ------------------------------------------------------------------------------

    /**
     * THE GRANT AND THE TOKEN HAVE TO AGREE, OR THE ROLE IS UNREACHABLE IN PRACTICE.
     *
     * <p>A row in {@code user_roles} confers nothing by itself. It becomes authority only when the
     * token encoder emits it and {@code AuthenticatedCallers} reads it back, and those two sit in
     * different modules with a published string contract between them (ADR-021). This walks the
     * whole path: promote, log in, and use the issued token on a PLATFORM_ADMIN-only route.
     *
     * <p>Without it, the encoder could emit {@code "PLATFORM_ADMIN:null"} and every test above
     * would still pass while no human could actually administer anything.
     */
    @Test
    void aPromotedUsersOwnTokenCarriesPlatformAuthority() throws Exception {
        clearPlatformAdmins();

        String email = email();
        UserId userId = registerUser(email);

        manageUserAccess.grantPlatformAdmin(userId, "test-operator");

        String accessToken = authentication.login(
            new LoginCommand(email, PASSWORD, "127.0.0.1")
        ).accessToken();

        UserId target = registerUser();

        mockMvc.perform(post("/api/v1/users/" + target.value() + "/platform-admin")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk());
    }

    /**
     * IT PROMOTES, IT NEVER CREATES. Inventing an account here would mean inventing a password, and
     * a privileged account with a credential nobody chose is one waiting to be guessed.
     */
    @Test
    void bootstrapCreatesNobodyForAnUnknownEmail() {
        clearPlatformAdmins();

        assertThat(manageUserAccess.bootstrapPlatformAdmin(email())).isEmpty();
        assertThat(totalPlatformAdmins()).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private UserId registerUser() {
        return registerUser(email());
    }

    private UserId registerUser(String email) {
        return registerUser.register(
            new RegisterUserCommand(email, PASSWORD, merchant().value(), "127.0.0.1")
        ).userId();
    }

    private UserId promoted() {
        UserId userId = registerUser();

        manageUserAccess.grantPlatformAdmin(userId, "test-operator");

        return userId;
    }

    private UserId onlyPlatformAdmin() {
        clearPlatformAdmins();

        return promoted();
    }

    private MerchantId merchant() {
        return merchants.register(new RegisterMerchantCommand(
            "Staff Co", "staff-" + UUID.randomUUID() + "@example.test", "IN", "INR"
        )).merchantId();
    }

    private MerchantId activatedMerchant() {
        MerchantId merchantId = merchant();

        changeMerchantStatus.activate(merchantId, "usr_test-operator", "Activated for test");

        return merchantId;
    }

    /**
     * Tests share one database, so a class that asserts on "how many platform admins exist" has to
     * start from a known number rather than from whatever ran before it.
     */
    private void clearPlatformAdmins() {
        jdbc.sql("delete from user_roles where merchant_id is null").update();
    }

    private void insertRole(UserId userId, String role, String merchantId) {
        jdbc.sql("insert into user_roles (user_id, role, merchant_id) values (?, ?, ?)")
            .params(userId.value(), role, merchantId)
            .update();
    }

    private long platformGrantCount(UserId userId) {
        return jdbc.sql("""
                select count(*) from user_roles
                 where user_id = ? and role = 'PLATFORM_ADMIN' and merchant_id is null
                """)
            .param(userId.value())
            .query(Long.class)
            .single();
    }

    private long totalPlatformAdmins() {
        return jdbc.sql(
                "select count(*) from user_roles where role = 'PLATFORM_ADMIN' and merchant_id is null")
            .query(Long.class)
            .single();
    }

    private static String email() {
        return "platform-" + UUID.randomUUID() + "@example.test";
    }

    /** No merchant suffix. That absence IS the platform claim (ADR-027). */
    private static RequestPostProcessor platformAdmin() {
        return caller("PLATFORM_ADMIN");
    }

    private static RequestPostProcessor merchantAdmin(MerchantId merchantId) {
        return caller("MERCHANT_ADMIN:" + merchantId.value());
    }

    private static RequestPostProcessor caller(String scopedRole) {
        return jwt().jwt(builder -> builder
            .subject("usr_" + UUID.randomUUID())
            .claim("roles", List.of(scopedRole)));
    }
}
