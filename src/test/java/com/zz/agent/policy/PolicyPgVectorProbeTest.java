package com.zz.agent.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in database probe; the test embedding is deterministic and has no semantic quality claim. */
@EnabledIfEnvironmentVariable(named = "AGENT_PGVECTOR_PROBE", matches = "true")
class PolicyPgVectorProbeTest {

    @Test
    void storesDocumentsAndAppliesMetadataFiltersWithJava() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getenv("AGENT_PGVECTOR_JDBC_URL"),
                System.getenv("AGENT_PGVECTOR_USER"),
                System.getenv("AGENT_PGVECTOR_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PgVectorStore store = PgVectorStore.builder(jdbc, new ProbeEmbeddingModel())
                .dimensions(1024)
                .indexType(PgVectorStore.PgIndexType.NONE)
                .initializeSchema(true)
                .build();
        store.afterPropertiesSet();

        List<PolicySource> policies = new PolicySourceLoader().load(Path.of("data/policies"));
        List<Document> documents = new PolicyVectorDocumentMapper().map(policies);
        store.add(documents);
        Document expected = documents.stream()
                .filter(document -> "POL-STORE-A-MX-7-TEST".equals(document.getMetadata().get("policyId")))
                .findFirst().orElseThrow();

        List<Document> matching = store.similaritySearch(SearchRequest.builder()
                .query(expected.getText()).topK(2)
                .filterExpression("storeId == 'STORE-A' && modelCode == 'MX-7'")
                .build());
        assertTrue(matching.stream().anyMatch(document -> document.getId().equals(expected.getId())));
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                matching.getFirst().getMetadata().get("sourceRef"));
        PolicyEvidenceGate.Decision evidence = new PolicyEvidenceGate().evaluate(policies,
                "STORE-A", "MX-7", OffsetDateTime.parse("2026-11-07T14:00:00+08:00"),
                "下午两点能去试车吗", matching);
        assertEquals(PolicyEvidenceGate.Outcome.CANDIDATES, evidence.outcome());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                evidence.evidence().getFirst().sourceRef());

        List<Document> wrongModel = store.similaritySearch(SearchRequest.builder()
                .query(expected.getText()).topK(2)
                .filterExpression("storeId == 'STORE-A' && modelCode == 'MX-999'")
                .build());
        assertTrue(wrongModel.isEmpty());
        assertEquals(1024, jdbc.queryForObject("SELECT vector_dims(embedding) FROM vector_store LIMIT 1", Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute("""
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES ('00000000-0000-0000-0000-000000000001', 'bad', '{}'::json, '[0,0,0]'::vector)
                """));
    }

    private static final class ProbeEmbeddingModel extends AbstractEmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> results = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                results.add(new Embedding(vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return 1024;
        }

        private static float[] vector(String text) {
            float[] values = new float[1024];
            for (int i = 0; i < text.length(); i++) {
                values[(text.charAt(i) * 31 + i) % values.length] += 1F;
            }
            return values;
        }
    }
}
