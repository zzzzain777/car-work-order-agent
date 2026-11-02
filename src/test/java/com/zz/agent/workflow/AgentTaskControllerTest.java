package com.zz.agent.workflow;

import com.zz.agent.integration.CarSystemReadClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 浏览器接口只能调用已有内部流程，且明确返回模拟状态。 */
class AgentTaskControllerTest {

    private static final String COOKIE = "SESSION=browser-session";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void tokenNeedsAdministratorAndAValidTokenAllowsTaskCreation() throws Exception {
        Fixture fixture = fixture();
        UUID taskId = UUID.randomUUID();
        JdbcAgentTaskStore.Task task = task(taskId);
        when(fixture.workflow.open(COOKIE, 1001)).thenReturn(task);

        fixture.mvc.perform(get("/api/agent/tasks/csrf"))
                .andExpect(status().isUnauthorized());
        fixture.mvc.perform(get("/api/agent/tasks/csrf").header("Cookie", COOKIE))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token").isNotEmpty());

        fixture.mvc.perform(post("/api/agent/tasks")
                        .header("Cookie", COOKIE)
                        .header("Origin", "http://localhost:8091")
                        .header("X-Agent-CSRF", fixture.csrf.issue(COOKIE).token())
                        .contentType("application/json")
                        .content("{\"workOrderId\":1001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId.toString()))
                .andExpect(jsonPath("$.state").value("NEEDS_INPUT"));
    }

    @Test
    void staleVersionCannotBeSavedAndDecisionIsExplicitlySimulated() throws Exception {
        Fixture fixture = fixture();
        UUID taskId = UUID.randomUUID();
        when(fixture.workflow.supplement(eq(COOKIE), eq(taskId), eq(9L), any()))
                .thenReturn(Optional.empty());
        when(fixture.confirmations.confirm(eq(COOKIE), eq(taskId), any()))
                .thenReturn(new SimulatedConfirmationFlow.Decision(TaskState.DEFINITELY_FAILED,
                        UUID.randomUUID(), "UNCHANGED"));
        String token = fixture.csrf.issue(COOKIE).token();

        fixture.mvc.perform(post("/api/agent/tasks/{id}/supplement", taskId)
                        .header("Cookie", COOKIE)
                        .header("Origin", "http://localhost:8091")
                        .header("X-Agent-CSRF", token)
                        .contentType("application/json")
                        .content("""
                                {"expectedVersion":9,"storeId":"STORE-A","modelCode":"MX-7",
                                 "appointmentAt":"2026-11-07T14:00:00+08:00"}
                                """))
                .andExpect(status().isConflict());

        fixture.mvc.perform(post("/api/agent/tasks/{id}/decision", taskId)
                        .header("Cookie", COOKIE)
                        .header("Origin", "http://localhost:8091")
                        .header("X-Agent-CSRF", token)
                        .contentType("application/json")
                        .content("""
                                {"proposalId":"00000000-0000-0000-0000-000000000001",
                                 "version":3,"approve":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.state").value("DEFINITELY_FAILED"));

        fixture.mvc.perform(post("/api/agent/tasks/{id}/decision", taskId)
                        .header("Cookie", COOKIE)
                        .header("X-Agent-CSRF", token)
                        .contentType("application/json")
                        .content("{\"proposalId\":\"x\",\"version\":3,\"approve\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void recommendationReturnsThePersistedAwaitingConfirmationVersion() throws Exception {
        Fixture fixture = fixture();
        UUID taskId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        JdbcAgentTaskStore.Task ready = new JdbcAgentTaskStore.Task(taskId, 9001, 1001, 1, 0, null,
                "2026-11-02 10:00:00", 2, TaskState.READY_FOR_ADMIN_REVIEW,
                new ReadOnlyAdviceFlow.Supplement("STORE-A", "MX-7", null), null, CLOCK.instant());
        JdbcAgentTaskStore.Task awaiting = new JdbcAgentTaskStore.Task(taskId, 9001, 1001, 1, 0, null,
                "2026-11-02 10:00:00", 3, TaskState.AWAITING_CONFIRMATION,
                ready.supplement(), null, CLOCK.instant());
        ReadOnlyAdviceFlow.Result preview = new ReadOnlyAdviceFlow.Result(
                ReadOnlyAdviceFlow.Stage.READY_FOR_ADMIN_REVIEW, 1001, "模拟建议", List.of(),
                List.of(), List.of(), CLOCK.instant(), false);
        StructuredAdviceService.Advice advice = new StructuredAdviceService.Advice(
                StructuredAdviceService.Outcome.CANDIDATE, 301L, 1, 2, List.of(),
                "请核对", "advice-v1", "deepseek-flash", 10, 5);
        AgentAdviceOrchestrator.Recommendation result =
                new AgentAdviceOrchestrator.Recommendation(ready, preview, advice);
        JdbcConfirmationStore.Proposal proposal = new JdbcConfirmationStore.Proposal(
                proposalId, taskId, 3, 301, "0".repeat(64), CLOCK.instant().plusSeconds(600));
        when(fixture.orchestrator.refreshAndSuggest(COOKIE, taskId, 1)).thenReturn(Optional.of(result));
        when(fixture.confirmations.publish(COOKIE, result)).thenReturn(Optional.of(proposal));
        when(fixture.workflow.find(COOKIE, taskId)).thenReturn(Optional.of(awaiting));

        fixture.mvc.perform(post("/api/agent/tasks/{id}/recommend", taskId)
                        .header("Cookie", COOKIE)
                        .header("Origin", "http://localhost:8091")
                        .header("X-Agent-CSRF", fixture.csrf.issue(COOKIE).token())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.version").value(3))
                .andExpect(jsonPath("$.task.state").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.proposal.id").value(proposalId.toString()));
    }

    private static JdbcAgentTaskStore.Task task(UUID id) {
        return new JdbcAgentTaskStore.Task(id, 9001, 1001, 1, 0, null,
                "2026-11-02 10:00:00", 0, TaskState.NEEDS_INPUT,
                new ReadOnlyAdviceFlow.Supplement(null, null, null), null, CLOCK.instant());
    }

    private static Fixture fixture() {
        AgentTaskWorkflow workflow = mock(AgentTaskWorkflow.class);
        AgentAdviceOrchestrator orchestrator = mock(AgentAdviceOrchestrator.class);
        SimulatedConfirmationFlow confirmations = mock(SimulatedConfirmationFlow.class);
        CarSystemReadClient car = mock(CarSystemReadClient.class);
        when(car.currentUser(COOKIE)).thenReturn(new CarSystemReadClient.Identity(9001, 2, 0));
        AgentTaskCsrf csrf = new AgentTaskCsrf(CLOCK);
        AgentTaskController controller = new AgentTaskController(workflow, orchestrator, confirmations, csrf, car);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new AgentTaskCsrfFilter(csrf))
                .defaultRequest(get("/").with(request -> {
                    request.setServerName("localhost");
                    request.setServerPort(8091);
                    return request;
                })).build();
        return new Fixture(workflow, orchestrator, confirmations, csrf, mvc);
    }

    private record Fixture(AgentTaskWorkflow workflow, AgentAdviceOrchestrator orchestrator,
                           SimulatedConfirmationFlow confirmations,
                           AgentTaskCsrf csrf, MockMvc mvc) {
    }
}
