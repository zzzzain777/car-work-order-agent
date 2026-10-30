package com.zz.agent.policy;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** 只检索本仓库的模拟政策，业务范围和原文校验始终由程序执行。 */
public final class PolicyRagService {

    private final VectorStore vectorStore;
    private final Path policyDirectory;
    private final PolicySourceLoader sourceLoader = new PolicySourceLoader();
    private final PolicyMetadataFilter metadataFilter = new PolicyMetadataFilter();
    private final PolicyVectorDocumentMapper documentMapper = new PolicyVectorDocumentMapper();
    private final PolicyEvidenceGate evidenceGate = new PolicyEvidenceGate();

    public PolicyRagService(VectorStore vectorStore, Path policyDirectory) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "向量库不能为空");
        this.policyDirectory = Objects.requireNonNull(policyDirectory, "政策目录不能为空");
    }

    public PolicyEvidenceGate.Decision search(String storeId, String modelCode,
                                              OffsetDateTime appointmentAt, String question) throws IOException {
        if (question == null || question.isBlank() || question.length() > 500) {
            throw new IllegalArgumentException("问题不能为空，且最多 500 字");
        }
        List<PolicySource> policies = sourceLoader.load(policyDirectory);
        PolicyMetadataFilter.Result applicable = metadataFilter.find(policies, storeId, modelCode, appointmentAt);

        // 预检只判断适用范围和问题类型；这里的原文片段不会直接作为检索结果返回。
        List<Document> originals = documentMapper.map(applicable.policies());
        PolicyEvidenceGate.Decision preflight = evidenceGate.evaluate(
                policies, storeId, modelCode, appointmentAt, question, originals);
        if (preflight.outcome() != PolicyEvidenceGate.Outcome.CANDIDATES) {
            return preflight;
        }

        PolicySource source = applicable.policies().getFirst();
        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(8)
                .filterExpression(filter.and(
                        filter.and(filter.eq("policyId", source.policyId()),
                                filter.eq("version", source.version())),
                        filter.and(filter.eq("storeId", storeId), filter.eq("modelCode", modelCode)))
                        .build())
                .build();
        List<Document> retrieved = vectorStore.similaritySearch(request);
        return evidenceGate.evaluate(policies, storeId, modelCode, appointmentAt, question, retrieved);
    }
}
