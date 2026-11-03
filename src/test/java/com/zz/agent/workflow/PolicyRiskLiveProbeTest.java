package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.model.ModelTextGenerator;
import com.zz.agent.policy.PolicyEvidenceGate;
import com.zz.agent.policy.PolicySourceLoader;
import com.zz.agent.policy.PolicyVectorDocumentMapper;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 显式执行的一次真实模型探针：核对正式模拟语料上的风险提示。 */
@EnabledIfSystemProperty(named = "policy.risk.live", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("deepseek")
class PolicyRiskLiveProbeTest {

    @Autowired
    private ModelTextGenerator generator;

    @Autowired
    private ObjectMapper json;

    @RepeatedTest(3)
    void currentSimplePolicyCanProduceCitedReadOnlyAllowance() throws Exception {
        var document = new PolicyVectorDocumentMapper()
                .map(new PolicySourceLoader().load(Path.of("data/policies"))).stream()
                .filter(item -> "data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1"
                        .equals(item.getMetadata().get("sourceRef")))
                .findFirst().orElseThrow();
        PolicyEvidenceGate.Evidence evidence = new PolicyEvidenceGate.Evidence(
                (String) document.getMetadata().get("chunkId"),
                (String) document.getMetadata().get("policyId"),
                ((Number) document.getMetadata().get("version")).intValue(),
                (String) document.getMetadata().get("sourceRef"), document.getText());
        PolicyRiskReviewService service = new PolicyRiskReviewService((system, user) -> {
            ChatResponse response = generator.generate(system, user);
            var metadata = response.getMetadata();
            var usage = metadata == null ? null : metadata.getUsage();
            return new StructuredAdviceService.Completion(
                    response.getResult().getOutput().getText(),
                    metadata == null ? null : metadata.getModel(),
                    usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens().longValue(),
                    usage == null || usage.getCompletionTokens() == null ? 0
                            : usage.getCompletionTokens().longValue());
        }, json, new PolicyRiskDiagnosticFileSink(json,
                Path.of("target/diagnostics/policy-risk-probe.jsonl")));

        PolicyRiskReviewService.Review review = service.assess(evidence, "STORE-A", "MX-7",
                OffsetDateTime.parse("2026-11-07T14:00:00+08:00"), true);

        assertEquals(PolicyRiskReviewService.Verdict.ALLOW, review.verdict(), review.message());
        assertEquals(evidence.sourceRef(), review.sourceRef());
        assertTrue(evidence.text().contains(review.quote()));
        System.out.printf("正式模拟政策风险探针：%s，模型=%s，输入=%d，输出=%d%n",
                review.verdict(), review.modelId(), review.promptTokens(), review.completionTokens());
    }
}
