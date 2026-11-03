package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.policy.PolicyEvidenceGate;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 政策风险提示必须有原文短引；异常和伪造引用不能放行。 */
class PolicyRiskReviewServiceTest {

    private static final String SOURCE = "data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1";
    private static final String TEXT = "MX-7 试驾申请时间为每日 10:00（含）至 17:00（不含），按 Asia/Shanghai 时区判断。";
    private static final PolicyEvidenceGate.Evidence EVIDENCE =
            new PolicyEvidenceGate.Evidence("chunk-1", "POL-A", 1, SOURCE, TEXT);

    @Test
    void acceptsCitedReadOnlyRiskVerdict() {
        PolicyRiskReviewService service = service("""
                {"verdict":"ALLOW","quote":"每日 10:00（含）至 17:00（不含）"}
                """);

        PolicyRiskReviewService.Review review = service.assess(EVIDENCE, "STORE-A", "MX-7",
                OffsetDateTime.parse("2026-11-07T14:00:00+08:00"));

        assertEquals(PolicyRiskReviewService.Verdict.ALLOW, review.verdict());
        assertEquals(SOURCE, review.sourceRef());
        assertEquals("每日 10:00（含）至 17:00（不含）", review.quote());
        assertEquals(80, review.promptTokens());
        assertEquals(20, review.completionTokens());
        assertTrue(review.message().contains("人工核对"));
    }

    @Test
    void fabricatedQuoteOrExtraFieldBecomesUnknown() {
        for (String output : new String[]{
                "{\"verdict\":\"ALLOW\",\"quote\":\"任何时候都能申请\"}",
                "{\"verdict\":\"ALLOW\",\"quote\":\"每日 10:00（含）至 17:00（不含）\",\"approve\":true}",
                "{\"verdict\":\"OTHER\",\"quote\":\"每日 10:00（含）至 17:00（不含）\"}"}) {
            PolicyRiskReviewService.Review review = service(output).assess(EVIDENCE, "STORE-A", "MX-7",
                    OffsetDateTime.parse("2026-11-07T14:00:00+08:00"));
            assertEquals(PolicyRiskReviewService.Verdict.UNKNOWN, review.verdict());
            assertNull(review.sourceRef());
            assertNull(review.quote());
        }
    }

    @Test
    void missingInputOrModelFailureBecomesUnknown() {
        AtomicInteger calls = new AtomicInteger();
        PolicyRiskReviewService service = new PolicyRiskReviewService((system, user) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("模拟模型不可用");
        }, new ObjectMapper());

        assertEquals(PolicyRiskReviewService.Verdict.UNKNOWN,
                service.assess(null, "STORE-A", "MX-7",
                        OffsetDateTime.parse("2026-11-07T14:00:00+08:00")).verdict());
        assertEquals(0, calls.get());
        assertEquals(PolicyRiskReviewService.Verdict.UNKNOWN,
                service.assess(EVIDENCE, "STORE-A", "MX-7",
                        OffsetDateTime.parse("2026-11-07T14:00:00+08:00")).verdict());
        assertEquals(1, calls.get());
    }

    private static PolicyRiskReviewService service(String output) {
        return new PolicyRiskReviewService((system, user) ->
                new StructuredAdviceService.Completion(output, "deepseek-flash", 80, 20),
                new ObjectMapper());
    }
}
