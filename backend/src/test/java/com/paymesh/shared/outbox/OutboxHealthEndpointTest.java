package com.paymesh.shared.outbox;

import com.paymesh.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * The ADR-025 alert as an operator actually sees it: over HTTP, on the endpoint they already poll.
 *
 * <h2>Why this test exists rather than trusting the unit test</h2>
 *
 * {@code OutboxBacklogHealthIndicatorTest} proves the indicator computes the right answer. It cannot
 * prove the answer is REACHABLE — that Boot registers the bean as a health contributor, that it
 * lands under the name anything scraping it will key on, or that the detail keys survive to the
 * JSON. All three are the sort of thing that works until a bean is renamed, and an alert nobody can
 * read is not an alert.
 * <p>
 * The component name is derived by Boot from the bean name with the {@code HealthIndicator} suffix
 * stripped, so renaming the {@code @Bean} method in {@code OutboxConfiguration} silently renames the
 * component and breaks whatever watches it. This pins it.
 *
 * <h2>Details are visible because this is the dev profile</h2>
 *
 * {@code application.yaml} sets {@code show-details: never} and production keeps it — component
 * internals are not for unauthenticated callers. {@code application-dev.yaml} overrides it, because
 * the two numbers ARE the alert and hiding them leaves a bare UP/DOWN. This test runs on dev, which
 * is the configuration it is asserting.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class OutboxHealthEndpointTest {

    private final MockMvc mockMvc;

    @Autowired
    OutboxHealthEndpointTest(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /**
     * <b>Sabotage that must turn this red:</b> rename the {@code outboxBacklogHealthIndicator} bean
     * method, or drop a {@code withDetail} key. Both compile, both pass every other test, and both
     * leave an operator watching a field that no longer exists.
     */
    @Test
    void reportsTheOutboxBacklogUnderActuatorHealth() throws Exception {
        // NOT andExpect(status().isOk()): a DOWN component makes the aggregate 503, and in this
        // suite other tests' undelivered events routinely make that happen. The status code is the
        // aggregate's business; this test is about the component being present and complete.
        mockMvc.perform(get("/actuator/health"))
            .andExpect(jsonPath("$.components.outboxBacklog").exists())
            .andExpect(jsonPath("$.components.outboxBacklog.details.deadLetteredEvents").exists())
            .andExpect(jsonPath("$.components.outboxBacklog.details.backlogStalled").exists())
            .andExpect(jsonPath("$.components.outboxBacklog.details.eventsAbandoned").exists())
            .andExpect(jsonPath("$.components.outboxBacklog.details.oldestUnpublishedAgeSeconds")
                .exists());
    }

    /**
     * THE REPORTED STATUS AGREES WITH THE DETAILS, whatever the suite happened to leave behind.
     *
     * <h2>Why this is not "assert UP"</h2>
     *
     * It was, and it failed — correctly. This suite shares one container, and other tests leave
     * unpublished events behind, so by the time this runs the oldest one routinely exceeds the
     * five-minute threshold and the indicator reports DOWN. <b>That is the indicator working.</b>
     * An endpoint test that demanded UP would be asserting that no other test had ever produced an
     * event, which is a fact about test ordering rather than about this code.
     * <p>
     * So this asserts the invariant that holds in every state instead: the component is DOWN exactly
     * when it says something is wrong, and UP exactly when it does not. A bug that reported DOWN
     * with both flags false — or UP while events were abandoned — is the one that would make the
     * alert lie, and it is caught here regardless of what ran first.
     * <p>
     * Whether the two flags are computed correctly is {@code OutboxBacklogHealthIndicatorTest}'s
     * job, with a fixed clock and no shared state.
     */
    @Test
    void reportsAStatusThatAgreesWithTheDetailsItPublishes() throws Exception {
        String body = mockMvc.perform(get("/actuator/health"))
            .andReturn().getResponse().getContentAsString();

        JsonNode outbox = new ObjectMapper().readTree(body).path("components").path("outboxBacklog");

        boolean unhealthy = outbox.path("details").path("backlogStalled").asBoolean()
            || outbox.path("details").path("eventsAbandoned").asBoolean();

        assertThat(outbox.path("status").asString())
            .as("a component that reports a problem must be DOWN, and one that does not must be UP")
            .isEqualTo(unhealthy ? "DOWN" : "UP");
    }
}
