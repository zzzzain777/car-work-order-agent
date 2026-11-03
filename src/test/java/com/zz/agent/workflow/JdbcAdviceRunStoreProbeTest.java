package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.integration.CarSystemReadClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 临时数据库验证指标确实落库，且不能跨管理员读取或写入旧版本。 */
@EnabledIfEnvironmentVariable(named = "AGENT_PGVECTOR_PROBE", matches = "true")
class JdbcAdviceRunStoreProbeTest {

    @Test
    void persistsMetricsAndScopesReadsToTheTaskAdministrator() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                System.getenv("AGENT_PGVECTOR_JDBC_URL"),
                System.getenv("AGENT_PGVECTOR_USER"),
                System.getenv("AGENT_PGVECTOR_PASSWORD")));
        JdbcAgentTaskStore tasks = new JdbcAgentTaskStore(jdbc, new ObjectMapper().findAndRegisterModules());
        tasks.initializeSchema();
        JdbcAdviceRunStore runs = new JdbcAdviceRunStore(jdbc);
        runs.initializeSchema();
        JdbcAgentTaskStore.Task task = tasks.create(9001,
                new CarSystemReadClient.WorkOrder(1001, 1, 0, null, "2026-11-03 10:00:00"));
        StructuredAdviceService.Advice advice = new StructuredAdviceService.Advice(
                StructuredAdviceService.Outcome.CANDIDATE, 301L, 0, 1,
                List.of(new StructuredAdviceService.Citation(
                        "data/policies/STORE-C-MX-7-test-drive-v1.md#rule-1", "10:00 至 17:00")),
                "模拟建议", StructuredAdviceService.PROMPT_VERSION, "deepseek-flash", 120, 35)
                .withPolicyRisk(new PolicyRiskReviewService.Review(PolicyRiskReviewService.Verdict.ALLOW,
                        "data/policies/STORE-C-MX-7-test-drive-v1.md#rule-1", "10:00 至 17:00",
                        "只读政策风险提示", PolicyRiskReviewService.PROMPT_VERSION, "deepseek-flash", 80, 20));

        JdbcAdviceRunStore.Run saved = runs.record(task, advice, 456).orElseThrow();

        assertNotNull(saved.createdAt());
        assertEquals(456, saved.requestElapsedMs());
        assertEquals(200, saved.promptTokens());
        assertEquals(55, saved.completionTokens());
        assertEquals("data/policies/STORE-C-MX-7-test-drive-v1.md#rule-1", saved.citedSourceRef());
        assertEquals("ALLOW", saved.riskVerdict());
        assertEquals("data/policies/STORE-C-MX-7-test-drive-v1.md#rule-1", saved.riskSourceRef());
        assertEquals("10:00 至 17:00", saved.riskQuote());
        assertTrue(runs.hasApprovedRiskRun(task.id(), 9001, task.version()));
        assertFalse(runs.hasApprovedRiskRun(task.id(), 9002, task.version()));
        assertEquals(List.of(saved), runs.recent(task.id(), 9001));
        assertTrue(runs.recent(task.id(), 9002).isEmpty());

        JdbcAgentTaskStore.Task stale = new JdbcAgentTaskStore.Task(task.id(), task.adminId(),
                task.workOrderId(), task.orderType(), task.orderStatus(), task.orderAdvisorId(),
                task.orderUpdatedAt(), task.version() + 1, task.state(), task.supplement(),
                task.preview(), task.updatedAt());
        assertTrue(runs.record(stale, advice, 457).isEmpty());
    }
}
