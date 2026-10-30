package com.zz.agent.policy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证政策范围先于向量召回，且返回片段必须与模拟原文一致。 */
class PolicyRagServiceTest {

    private static final Path POLICY_DIRECTORY = Path.of("data/policies");
    private static final OffsetDateTime APPOINTMENT = OffsetDateTime.parse("2026-11-07T14:00:00+08:00");

    @Test
    void returnsCanonicalPolicySourceAfterVectorSearch() throws Exception {
        VectorStore store = mock(VectorStore.class);
        List<PolicySource> sources = new PolicySourceLoader().load(POLICY_DIRECTORY);
        Document correct = new PolicyVectorDocumentMapper().map(sources).stream()
                .filter(doc -> "POL-STORE-A-MX-7-TEST".equals(doc.getMetadata().get("policyId")))
                .findFirst().orElseThrow();
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(correct));

        PolicyEvidenceGate.Decision result = new PolicyRagService(store, POLICY_DIRECTORY)
                .search("STORE-A", "MX-7", APPOINTMENT, "下午两点能去试车吗");

        assertEquals(PolicyEvidenceGate.Outcome.CANDIDATES, result.outcome());
        assertEquals("POL-STORE-A-MX-7-TEST", result.evidence().getFirst().policyId());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                result.evidence().getFirst().sourceRef());
    }

    @Test
    void wrongModelAndExpiredPolicyDoNotCallVectorSearch() throws Exception {
        VectorStore store = mock(VectorStore.class);
        PolicyRagService service = new PolicyRagService(store, POLICY_DIRECTORY);

        assertEquals(PolicyEvidenceGate.Outcome.NO_APPLICABLE_POLICY,
                service.search("STORE-C", "MX-70", APPOINTMENT, "试驾规则").outcome());
        assertEquals(PolicyEvidenceGate.Outcome.NO_APPLICABLE_POLICY,
                service.search("STORE-D", "MX-7", OffsetDateTime.parse("2026-10-20T00:00:00+08:00"),
                        "试驾申请时间").outcome());
        verify(store, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void conflictAndUnsupportedTopicStopBeforeVectorSearch() throws Exception {
        VectorStore store = mock(VectorStore.class);
        PolicyRagService service = new PolicyRagService(store, POLICY_DIRECTORY);

        PolicyEvidenceGate.Decision conflict = service.search("STORE-E", "MX-7", APPOINTMENT, "现在还能约吗");
        assertEquals(PolicyEvidenceGate.Outcome.REVIEW_REQUIRED, conflict.outcome());
        assertEquals(2, conflict.evidence().size());
        PolicyEvidenceGate.Decision price = service.search("STORE-A", "MX-7", APPOINTMENT,
                "MX-7 价格优惠多少");
        assertEquals(PolicyEvidenceGate.Outcome.NO_EVIDENCE, price.outcome());
        assertTrue(price.evidence().isEmpty());
        verify(store, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void rejectsVectorResultThatDoesNotMatchOriginalPolicy() throws Exception {
        VectorStore store = mock(VectorStore.class);
        List<PolicySource> sources = new PolicySourceLoader().load(POLICY_DIRECTORY);
        Document original = new PolicyVectorDocumentMapper().map(sources).stream()
                .filter(doc -> "POL-STORE-A-MX-7-TEST".equals(doc.getMetadata().get("policyId")))
                .findFirst().orElseThrow();
        Document tampered = new Document(original.getId(), "伪造的政策内容", original.getMetadata());
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(tampered));

        PolicyEvidenceGate.Decision result = new PolicyRagService(store, POLICY_DIRECTORY)
                .search("STORE-A", "MX-7", APPOINTMENT, "下午两点能去试车吗");

        assertEquals(PolicyEvidenceGate.Outcome.NO_EVIDENCE, result.outcome());
        assertTrue(result.evidence().isEmpty());
    }
}
