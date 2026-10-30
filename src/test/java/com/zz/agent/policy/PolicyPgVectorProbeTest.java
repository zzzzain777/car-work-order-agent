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
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可选的数据库探针；测试向量可重复生成，不用于证明语义检索质量。 */
@EnabledIfEnvironmentVariable(named = "AGENT_PGVECTOR_PROBE", matches = "true")
class PolicyPgVectorProbeTest {

    @Test
    void repeatedIndexingSkipsUnchangedPoliciesAndRemovesStaleChunks(@org.junit.jupiter.api.io.TempDir Path temp)
            throws Exception {
        Files.copy(Path.of("data/policies/STORE-A-MX-7-test-drive-v1.md"),
                temp.resolve("STORE-A-MX-7-test-drive-v1.md"));
        Files.copy(Path.of("data/policies/STORE-A-MX-70-test-drive-v1.md"),
                temp.resolve("STORE-A-MX-70-test-drive-v1.md"));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                System.getenv("AGENT_PGVECTOR_JDBC_URL"),
                System.getenv("AGENT_PGVECTOR_USER"),
                System.getenv("AGENT_PGVECTOR_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ProbeEmbeddingModel model = new ProbeEmbeddingModel();
        PgVectorStore store = PgVectorStore.builder(jdbc, model)
                .dimensions(1024)
                .indexType(PgVectorStore.PgIndexType.NONE)
                .vectorTableName("policy_vector_store")
                .initializeSchema(true)
                .build();
        store.afterPropertiesSet();
        PolicyIndexService index = new PolicyIndexService(store, jdbc, temp, "probe-embedding-1024");

        assertTrue(index.refresh().changed());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM policy_vector_store", Integer.class));
        int firstEmbeddings = model.embeddingCalls.get();
        assertTrue(firstEmbeddings > 0);
        assertTrue(!index.refresh().changed());
        assertEquals(firstEmbeddings, model.embeddingCalls.get());

        Files.delete(temp.resolve("STORE-A-MX-70-test-drive-v1.md"));
        assertTrue(index.refresh().changed());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM policy_vector_store", Integer.class));
    }

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
        private final AtomicInteger embeddingCalls = new AtomicInteger();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            embeddingCalls.incrementAndGet();
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
