package com.zz.agent.policy;

import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static java.util.Map.entry;

/** Converts versioned simulated policy rules into traceable vector documents. */
public final class PolicyVectorDocumentMapper {

    private final PolicyRuleChunker chunker = new PolicyRuleChunker();

    public List<Document> map(List<PolicySource> sources) {
        Objects.requireNonNull(sources, "sources");
        List<Document> documents = new ArrayList<>();
        for (PolicySource source : sources) {
            for (PolicyRuleChunker.Chunk chunk : chunker.split(source)) {
                String id = UUID.nameUUIDFromBytes(chunk.id().getBytes(StandardCharsets.UTF_8)).toString();
                Map<String, Object> metadata = Map.ofEntries(
                        entry("chunkId", chunk.id()),
                        entry("policyId", source.policyId()),
                        entry("version", source.version()),
                        entry("sourceRef", chunk.sourceRef()),
                        entry("storeId", source.storeId()),
                        entry("modelCode", source.modelCode()),
                        entry("ruleType", source.ruleType()),
                        entry("status", source.status()),
                        entry("simulated", source.simulated()),
                        entry("effectiveFromEpoch", source.effectiveFrom().toEpochSecond()),
                        entry("effectiveToEpoch", source.effectiveTo().toEpochSecond())
                );
                documents.add(new Document(id, chunk.text(), metadata));
            }
        }
        return List.copyOf(documents);
    }
}
