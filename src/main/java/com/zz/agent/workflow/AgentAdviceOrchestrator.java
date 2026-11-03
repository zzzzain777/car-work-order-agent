package com.zz.agent.workflow;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 从持久化任务刷新只读证据，再生成受程序校验的模型候选建议。 */
public final class AgentAdviceOrchestrator {

    public record Recommendation(JdbcAgentTaskStore.Task task, ReadOnlyAdviceFlow.Result preview,
                                 StructuredAdviceService.Advice advice) {
    }

    private final AgentTaskWorkflow workflow;
    private final StructuredAdviceService advice;
    private final PolicyRiskReviewService policyRisk;

    public AgentAdviceOrchestrator(AgentTaskWorkflow workflow, StructuredAdviceService advice,
                                   PolicyRiskReviewService policyRisk) {
        this.workflow = Objects.requireNonNull(workflow, "任务流程不能为空");
        this.advice = Objects.requireNonNull(advice, "结构化建议服务不能为空");
        this.policyRisk = Objects.requireNonNull(policyRisk, "政策风险服务不能为空");
    }

    public Optional<Recommendation> refreshAndSuggest(String cookieHeader, UUID taskId, long expectedVersion) {
        return workflow.refresh(cookieHeader, taskId, expectedVersion)
                .map(refreshed -> {
                    StructuredAdviceService.Advice initial = advice.suggest(refreshed);
                    if (initial.outcome() != StructuredAdviceService.Outcome.CANDIDATE) {
                        return new Recommendation(refreshed.task(), refreshed.result(), initial);
                    }
                    var supplement = refreshed.task().supplement();
                    PolicyRiskReviewService.Review review = policyRisk.assess(
                            refreshed.result().policyEvidence().getFirst(), supplement.storeId(),
                            supplement.modelCode(), supplement.appointmentAt(),
                            refreshed.task().storeChoice() != null);
                    return new Recommendation(refreshed.task(), refreshed.result(), initial.withPolicyRisk(review));
                });
    }
}
