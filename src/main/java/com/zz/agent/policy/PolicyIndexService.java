package com.zz.agent.policy;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 将已校验的模拟政策同步到专用向量表；语料不变时避免重复消耗模型额度。 */
public final class PolicyIndexService {

    private static final String INDEX_NAME = "simulated-policy-v1";

    public record Result(boolean changed, int documents) {
    }

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final Path policyDirectory;
    private final String embeddingModelId;
    private final PolicySourceLoader loader = new PolicySourceLoader();
    private final PolicyVectorDocumentMapper mapper = new PolicyVectorDocumentMapper();

    public PolicyIndexService(VectorStore vectorStore, JdbcTemplate jdbc, Path policyDirectory,
                              String embeddingModelId) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "向量库不能为空");
        this.jdbc = Objects.requireNonNull(jdbc, "数据库连接不能为空");
        this.policyDirectory = Objects.requireNonNull(policyDirectory, "政策目录不能为空");
        this.embeddingModelId = Objects.requireNonNull(embeddingModelId, "向量模型编号不能为空");
    }

    public Result refresh() throws IOException {
        List<PolicySource> policies = loader.load(policyDirectory);
        List<Document> documents = mapper.map(policies);
        if (documents.isEmpty()) {
            throw new IllegalStateException("模拟政策目录为空，拒绝清空已有索引");
        }

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_policy_index_state (
                    name text PRIMARY KEY,
                    fingerprint text NOT NULL
                )
                """);
        String fingerprint = fingerprint(policies, documents);
        List<String> saved = jdbc.query("SELECT fingerprint FROM agent_policy_index_state WHERE name = ?",
                (rs, row) -> rs.getString(1), INDEX_NAME);
        Map<String, String> indexed = jdbc.query("SELECT id::text, content FROM policy_vector_store",
                rs -> {
                    Map<String, String> rows = new TreeMap<>();
                    while (rs.next()) {
                        rows.put(rs.getString(1), rs.getString(2));
                    }
                    return rows;
                });
        Map<String, String> desired = documents.stream()
                .collect(Collectors.toMap(Document::getId, Document::getText));
        if (saved.size() == 1 && saved.getFirst().equals(fingerprint) && indexed.equals(desired)) {
            return new Result(false, documents.size());
        }

        // 先完成新增和更新，再删除已不在模拟语料中的片段；失败时不更新索引指纹。
        vectorStore.add(documents);
        Set<String> desiredIds = desired.keySet();
        List<String> staleIds = indexed.keySet().stream().filter(id -> !desiredIds.contains(id)).toList();
        if (!staleIds.isEmpty()) {
            vectorStore.delete(staleIds);
        }
        jdbc.update("""
                INSERT INTO agent_policy_index_state (name, fingerprint) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET fingerprint = EXCLUDED.fingerprint
                """, INDEX_NAME, fingerprint);
        return new Result(true, documents.size());
    }

    private String fingerprint(List<PolicySource> policies, List<Document> documents) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, embeddingModelId);
            for (PolicySource policy : policies) {
                String fileName = Path.of(policy.sourceRef().split("#", 2)[0]).getFileName().toString();
                update(digest, fileName);
                digest.update(Files.readAllBytes(policyDirectory.resolve(fileName)));
                digest.update((byte) 0);
            }
            for (Document document : documents) {
                update(digest, document.getId());
                update(digest, document.getText());
                for (var entry : new TreeMap<>(document.getMetadata()).entrySet()) {
                    update(digest, entry.getKey());
                    update(digest, String.valueOf(entry.getValue()));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
