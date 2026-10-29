package com.zz.agent.policy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEvidenceGateTest {

    @Test
    void trialQuestionAboutDocumentsIsNotSupportedByTheTimeRule() throws Exception {
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        Document trialRule = new PolicyVectorDocumentMapper().map(policies).stream()
                .filter(document -> "POL-STORE-A-MX-7-TEST".equals(document.getMetadata().get("policyId")))
                .findFirst().orElseThrow();

        PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate().evaluate(
                policies, "STORE-A", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                "试驾需要带什么证件", List.of(trialRule));

        assertEquals(PolicyEvidenceGate.Outcome.NO_EVIDENCE, decision.outcome());
        assertTrue(decision.evidence().isEmpty());
    }

    @Test
    void unrelatedQuestionContainingTheCharacterYueDoesNotBecomeATrialQuestion() throws Exception {
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        Document trialRule = new PolicyVectorDocumentMapper().map(policies).stream()
                .filter(document -> "POL-STORE-A-MX-7-TEST".equals(document.getMetadata().get("policyId")))
                .findFirst().orElseThrow();

        PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate().evaluate(
                policies, "STORE-A", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                "违约责任是什么", List.of(trialRule));

        assertEquals(PolicyEvidenceGate.Outcome.NO_EVIDENCE, decision.outcome());
        assertTrue(decision.evidence().isEmpty());
    }

    @Test
    void rejectsAHitWhoseModelMetadataDisagreesWithTheSource() throws Exception {
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        Document trialRule = new PolicyVectorDocumentMapper().map(policies).stream()
                .filter(document -> "POL-STORE-A-MX-7-TEST".equals(document.getMetadata().get("policyId")))
                .findFirst().orElseThrow();
        Map<String, Object> alteredMetadata = new HashMap<>(trialRule.getMetadata());
        alteredMetadata.put("modelCode", "MX-70");
        Document corruptedHit = new Document(trialRule.getId(), trialRule.getText(), alteredMetadata);

        PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate().evaluate(
                policies, "STORE-A", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                "MX-7 试驾时间", List.of(corruptedHit));

        assertEquals(PolicyEvidenceGate.Outcome.NO_EVIDENCE, decision.outcome());
        assertTrue(decision.evidence().isEmpty());
    }

    @Test
    void applicationPermissionQuestionPreservesConflictingPoliciesForReview() throws Exception {
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));

        PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate().evaluate(
                policies, "STORE-E", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                "允许申请", List.of());

        assertEquals(PolicyEvidenceGate.Outcome.REVIEW_REQUIRED, decision.outcome());
        assertEquals(2, decision.evidence().size());
    }

    @Test
    void trialPolicyCannotProveLiveVehicleOrSlotAvailability() throws Exception {
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        Document trialRule = new PolicyVectorDocumentMapper().map(policies).stream()
                .filter(document -> "POL-STORE-A-MX-7-TEST".equals(document.getMetadata().get("policyId")))
                .findFirst().orElseThrow();

        PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate().evaluate(
                policies, "STORE-A", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                "现在有试驾车和空闲时段吗", List.of(trialRule));

        assertEquals(PolicyEvidenceGate.Outcome.NO_EVIDENCE, decision.outcome());
        assertTrue(decision.evidence().isEmpty());
    }

    @Test
    void mixedTrialAndDiscountQuestionStillHasNoEvidence() throws Exception {
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        Document trialRule = new PolicyVectorDocumentMapper().map(policies).stream()
                .filter(document -> "POL-STORE-A-MX-7-TEST".equals(document.getMetadata().get("policyId")))
                .findFirst().orElseThrow();

        PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate().evaluate(
                policies, "STORE-A", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                "试驾优惠多少钱", List.of(trialRule));

        assertEquals(PolicyEvidenceGate.Outcome.NO_EVIDENCE, decision.outcome());
        assertTrue(decision.evidence().isEmpty());
    }

    @Test
    void conflictingApplicablePoliciesGoToReviewEvenWhenRetrieverMissesOne() throws Exception {
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        Document oneRetrievedRule = new PolicyVectorDocumentMapper().map(policies).stream()
                .filter(document -> "POL-STORE-E-MX-7-ALLOW".equals(document.getMetadata().get("policyId")))
                .findFirst().orElseThrow();

        PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate().evaluate(
                policies, "STORE-E", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                "现在还能约吗", List.of(oneRetrievedRule));

        assertEquals(PolicyEvidenceGate.Outcome.REVIEW_REQUIRED, decision.outcome());
        assertEquals(Set.of("POL-STORE-E-MX-7-ALLOW", "POL-STORE-E-MX-7-DENY"),
                decision.evidence().stream().map(PolicyEvidenceGate.Evidence::policyId).collect(Collectors.toSet()));
        assertEquals(Set.of("data/policies/STORE-E-MX-7-allow-v1.md#rule-1",
                        "data/policies/STORE-E-MX-7-deny-v1.md"),
                decision.evidence().stream().map(PolicyEvidenceGate.Evidence::sourceRef).collect(Collectors.toSet()));
    }

    @Test
    void trialQuestionKeepsARelevantChunkAndItsOriginalCitation() throws Exception {
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        Document trialRule = new PolicyVectorDocumentMapper().map(policies).stream()
                .filter(document -> "POL-STORE-A-MX-7-TEST".equals(document.getMetadata().get("policyId")))
                .findFirst().orElseThrow();

        PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate().evaluate(
                policies, "STORE-A", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                "下午两点能去试车吗", List.of(trialRule));

        assertEquals(PolicyEvidenceGate.Outcome.CANDIDATES, decision.outcome());
        assertEquals(1, decision.evidence().size());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                decision.evidence().getFirst().sourceRef());
        assertTrue(decision.evidence().getFirst().text().contains("10:00（含）至 17:00（不含）"));
    }

    @Test
    void priceQuestionCannotUseTrialRuleAsEvidence() throws Exception {
        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        Document trialRule = new PolicyVectorDocumentMapper().map(policies).stream()
                .filter(document -> "POL-STORE-A-MX-7-TEST".equals(document.getMetadata().get("policyId")))
                .findFirst().orElseThrow();

        PolicyEvidenceGate.Decision decision = new PolicyEvidenceGate().evaluate(
                policies, "STORE-A", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                "STORE-A MX-7 价格优惠多少", List.of(trialRule));

        assertEquals(PolicyEvidenceGate.Outcome.NO_EVIDENCE, decision.outcome());
        assertTrue(decision.evidence().isEmpty());
    }
}
