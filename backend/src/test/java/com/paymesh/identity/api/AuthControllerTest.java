package com.paymesh.identity.api;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.identity.application.AccessTokenClaims;
import com.paymesh.identity.application.AccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every test runs in a transaction that is rolled back afterwards, so the fixed
 * emails below cannot collide across runs.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private final MockMvc mockMvc;
    private final AccessTokenService accessTokenService;

    @Autowired
    AuthControllerTest(MockMvc mockMvc, AccessTokenService accessTokenService) {
        this.mockMvc = mockMvc;
        this.accessTokenService = accessTokenService;
    }

    // --- register ------------------------------------------------------------

    @Test
    void registersUser() throws Exception {
        mockMvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email": "Owner@FreshBrew.Example",
                          "password": "%s",
                          "merchantId": "mrc_550e8400-e29b-41d4-a716-446655440000"
                        }
                        """.formatted(PASSWORD))
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(matchesPattern("usr_[0-9a-fA-F-]{36}")))
            .andExpect(jsonPath("$.email").value("owner@freshbrew.example"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.roles[0].role").value("MERCHANT_ADMIN"))
            .andExpect(
                jsonPath("$.roles[0].merchantId")
                    .value("mrc_550e8400-e29b-41d4-a716-446655440000")
            )
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.updatedAt").exists())
            // The one field that must never appear in a response.
            .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void returnsConflictWhenEmailAlreadyRegistered() throws Exception {
        register("duplicate@freshbrew.example");

        mockMvc.perform(registerRequest("duplicate@freshbrew.example"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USER_EMAIL_ALREADY_EXISTS"))
            .andExpect(
                jsonPath("$.message")
                    .value("A user already exists with email duplicate@freshbrew.example")
            );
    }

    @Test
    void returnsBadRequestWhenPasswordIsTooShort() throws Exception {
        mockMvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email": "short@freshbrew.example",
                          "password": "short"
                        }
                        """)
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(
                jsonPath("$.fieldErrors.password")
                    .value("Password must be between 12 and 72 characters")
            );
    }

    @Test
    void returnsBadRequestWhenEmailIsNotAnEmailAddress() throws Exception {
        mockMvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email": "not-an-email",
                          "password": "%s"
                        }
                        """.formatted(PASSWORD))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(
                jsonPath("$.fieldErrors.email").value("Email must be a valid email address")
            );
    }

    // --- login ---------------------------------------------------------------

    @Test
    void logsInAndReturnsAVerifiableAccessToken() throws Exception {
        register("login@freshbrew.example");

        String body = mockMvc.perform(loginRequest("login@freshbrew.example", PASSWORD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andReturn()
            .getResponse()
            .getContentAsString();

        AccessTokenClaims claims = accessTokenService.verify(readField(body, "accessToken"));

        assertEquals("login@freshbrew.example", claims.email());
    }

    @Test
    void returnsUnauthorizedForTheWrongPassword() throws Exception {
        register("wrongpass@freshbrew.example");

        mockMvc.perform(loginRequest("wrongpass@freshbrew.example", "definitely-not-it-at-all"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }

    /** Identical body to the wrong-password case: no account enumeration. */
    @Test
    void returnsUnauthorizedForAnUnknownEmail() throws Exception {
        mockMvc.perform(loginRequest("nobody@freshbrew.example", PASSWORD))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }

    @Test
    void returnsBadRequestWhenLoginBodyIsIncomplete() throws Exception {
        mockMvc.perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email": "login@freshbrew.example"
                        }
                        """)
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.password").value("Password is required"));
    }

    // --- refresh -------------------------------------------------------------

    @Test
    void rotatesTheRefreshToken() throws Exception {
        register("rotate@freshbrew.example");

        String firstRefreshToken = refreshTokenFrom(login("rotate@freshbrew.example"));

        String rotated = mockMvc.perform(refreshRequest("/token/refresh", firstRefreshToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertEquals(false, firstRefreshToken.equals(readField(rotated, "refreshToken")));
    }

    /**
     * The whole point of rotation: replaying a spent token kills the family, so
     * the live successor the honest client holds stops working too.
     */
    @Test
    void reusingARotatedRefreshTokenRevokesTheWholeFamily() throws Exception {
        register("reuse@freshbrew.example");

        String first = refreshTokenFrom(login("reuse@freshbrew.example"));

        String second = readField(
            mockMvc.perform(refreshRequest("/token/refresh", first))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "refreshToken"
        );

        mockMvc.perform(refreshRequest("/token/refresh", first))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(refreshRequest("/token/refresh", second))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void returnsUnauthorizedForAnUnknownRefreshToken() throws Exception {
        mockMvc.perform(refreshRequest("/token/refresh", "not-a-token-anyone-issued"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
            .andExpect(
                jsonPath("$.message").value("Refresh token is invalid or has expired.")
            );
    }

    @Test
    void returnsBadRequestWhenRefreshTokenIsMissing() throws Exception {
        mockMvc.perform(
                post("/api/v1/auth/token/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.refreshToken").value("Refresh token is required"));
    }

    // --- logout --------------------------------------------------------------

    @Test
    void logoutRevokesTheSessionSoRefreshFails() throws Exception {
        register("logout@freshbrew.example");

        String refreshToken = refreshTokenFrom(login("logout@freshbrew.example"));

        mockMvc.perform(refreshRequest("/logout", refreshToken))
            .andExpect(status().isNoContent());

        mockMvc.perform(refreshRequest("/token/refresh", refreshToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    /** Idempotent, so it never reveals whether a token was live. */
    @Test
    void logoutReturnsNoContentForAnUnknownToken() throws Exception {
        mockMvc.perform(refreshRequest("/logout", "not-a-token-anyone-issued"))
            .andExpect(status().isNoContent());
    }

    // --- helpers -------------------------------------------------------------

    private void register(String email) throws Exception {
        mockMvc.perform(registerRequest(email)).andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        return mockMvc.perform(loginRequest(email, PASSWORD))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    private static org.springframework.test.web.servlet.RequestBuilder registerRequest(
        String email
    ) {
        return post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, PASSWORD));
    }

    private static org.springframework.test.web.servlet.RequestBuilder loginRequest(
        String email,
        String password
    ) {
        return post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password));
    }

    private static org.springframework.test.web.servlet.RequestBuilder refreshRequest(
        String path,
        String refreshToken
    ) {
        return post("/api/v1/auth" + path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "refreshToken": "%s"
                }
                """.formatted(refreshToken));
    }

    private static String refreshTokenFrom(String responseBody) {
        return readField(responseBody, "refreshToken");
    }

    private static String readField(String responseBody, String field) {
        return new ObjectMapper().readTree(responseBody).get(field).asString();
    }
}
