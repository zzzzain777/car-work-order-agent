package com.zz.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zz.agent.policy.PolicyEvidenceGate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
    void missingVerifiedCustomerChoiceStopsBeforeRiskModel() {
        AtomicInteger calls = new AtomicInteger();
        PolicyRiskReviewService service = new PolicyRiskReviewService((system, user) -> {
            calls.incrementAndGet();
            return new StructuredAdviceService.Completion("""
                    {"verdict":"ALLOW","quote":"每日 10:00（含）至 17:00（不含）"}
                    """, "fake", 1, 1);
        }, new ObjectMapper());

        PolicyRiskReviewService.Review review = service.assess(EVIDENCE, "STORE-A", "MX-7",
                OffsetDateTime.parse("2026-11-07T14:00:00+08:00"));

        assertEquals(PolicyRiskReviewService.Verdict.UNKNOWN, review.verdict());
        assertTrue(review.message().contains("客户本人选店"));
        assertEquals(0, calls.get());
    }

    @Test
    void acceptsCitedReadOnlyRiskVerdict() {
        PolicyRiskReviewService service = new PolicyRiskReviewService((system, user) -> {
            assertTrue(user.contains("\"customerSelectionRecorded\":true"));
            return new StructuredAdviceService.Completion("""
                    {"verdict":"ALLOW","quote":"每日 10:00（含）至 17:00（不含）"}
                    """, "deepseek-flash", 80, 20);
        }, new ObjectMapper());

        PolicyRiskReviewService.Review review = service.assess(EVIDENCE, "STORE-A", "MX-7",
                OffsetDateTime.parse("2026-11-07T14:00:00+08:00"), true);

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
                    OffsetDateTime.parse("2026-11-07T14:00:00+08:00"), true);
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
                        OffsetDateTime.parse("2026-11-07T14:00:00+08:00"), true).verdict());
        assertEquals(0, calls.get());
        assertEquals(PolicyRiskReviewService.Verdict.UNKNOWN,
                service.assess(EVIDENCE, "STORE-A", "MX-7",
                        OffsetDateTime.parse("2026-11-07T14:00:00+08:00"), true).verdict());
        assertEquals(1, calls.get());
    }

    @Test
    void observedDenialCitingOnlyAllowedWindowNeedsHumanReview() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var fixed = json.readTree(Path.of("data/eval/workflow/policy-risk-contradictory-denial-v1.json").toFile());
        var evidence = new PolicyEvidenceGate.Evidence("fixed-pr09", "POL-STORE-A-MX-7-TEST", 1,
                fixed.path("sourceRef").asText(), fixed.path("policyText").asText());
        var response = fixed.path("modelOutput");
        var service = service(response.toString());

        var review = service.assess(evidence, fixed.path("storeId").asText(),
                fixed.path("modelCode").asText(), OffsetDateTime.parse(fixed.path("appointmentAt").asText()), true);

        assertEquals(PolicyRiskReviewService.Verdict.UNKNOWN, review.verdict());
        assertEquals(evidence.sourceRef(), review.sourceRef());
        assertEquals(response.path("quote").asText(), review.quote());
        assertTrue(review.message().contains("引用"));
    }

    @Test
    void outsideCitedWindowCanStillBeDenied() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var fixed = json.readTree(Path.of("data/eval/workflow/policy-risk-contradictory-denial-v1.json").toFile());
        var evidence = new PolicyEvidenceGate.Evidence("fixed-pr09", "POL-STORE-A-MX-7-TEST", 1,
                fixed.path("sourceRef").asText(), fixed.path("policyText").asText());

        var review = service(fixed.path("modelOutput").toString()).assess(evidence, "STORE-A", "MX-7",
                OffsetDateTime.parse("2026-11-07T18:00:00+08:00"), true);

        assertEquals(PolicyRiskReviewService.Verdict.DENY, review.verdict());
    }

    @Test
    void quoteIncludingSeparateRequiredConditionIsNotMistakenForTimeOnlyQuote() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var fixed = json.readTree(Path.of("data/eval/workflow/policy-risk-contradictory-denial-v1.json").toFile());
        var evidence = new PolicyEvidenceGate.Evidence("fixed-pr09", "POL-STORE-A-MX-7-TEST", 1,
                fixed.path("sourceRef").asText(), fixed.path("policyText").asText());
        String citedRequirement = "在 `STORE-A`，`MX-7` 的试驾申请时间可落在每日 10:00（含）至 17:00（不含），"
                + "按 `Asia/Shanghai` 时区判断。顾客必须先选定门店和准确试驾时间。";

        var review = service("{\"verdict\":\"DENY\",\"quote\":"
                + json.writeValueAsString(citedRequirement) + "}").assess(evidence, "STORE-A", "MX-7",
                OffsetDateTime.parse("2026-11-07T14:00:00+08:00"), true);

        assertEquals(PolicyRiskReviewService.Verdict.DENY, review.verdict());
    }

    private static PolicyRiskReviewService service(String output) {
        return new PolicyRiskReviewService((system, user) ->
                new StructuredAdviceService.Completion(output, "deepseek-flash", 80, 20),
                new ObjectMapper());
    }
}
