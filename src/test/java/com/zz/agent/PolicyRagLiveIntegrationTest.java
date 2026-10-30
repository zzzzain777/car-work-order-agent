package com.zz.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.zz.agent.policy.PolicyIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 仅显式开启时连接本地数据库和百炼免费向量模型。 */
@EnabledIfSystemProperty(named = "policy.rag.live", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"policy-rag", "bailian-embedding"})
class PolicyRagLiveIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PolicyIndexService index;

    @Test
    void retrievesSimulatedPolicyFromRealVectorStoreWithoutReindexing() throws Exception {
        assertFalse(index.refresh().changed(), "相同语料不应重复生成向量");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/policies/search",
                new HttpEntity<>("""
                        {"storeId":"STORE-A","modelCode":"MX-7",
                         "appointmentAt":"2026-11-07T14:00:00+08:00", "question":"下午两点能去试车吗"}
                        """, headers), JsonNode.class);

        assertEquals(200, response.getStatusCode().value());
        JsonNode body = response.getBody();
        assertNotNull(body);
        assertEquals("CANDIDATES", body.path("outcome").asText());
        assertEquals("POL-STORE-A-MX-7-TEST", body.path("evidence").path(0).path("policyId").asText());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                body.path("evidence").path(0).path("sourceRef").asText());
    }

    @Test
    void replaysFixedPolicyCasesAgainstRealModelAndDatabase() throws Exception {
        Path casesPath = Path.of("data/eval/retrieval/keyword-holdout-v1.tsv");
        List<String> lines = Files.readAllLines(casesPath, StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        StringBuilder report = new StringBuilder("案例\t预期结果\t实际结果\t预期政策\t实际政策\t预期出处\t实际出处\t通过\n");
        int passed = 0;
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            String expected = switch (cells[5]) {
                case "MATCHES" -> "CANDIDATES";
                case "NO_KEYWORD_MATCH" -> "NO_EVIDENCE";
                default -> cells[5];
            };
            ResponseEntity<JsonNode> response = rest.postForEntity("/api/policies/search",
                    new HttpEntity<>(new PolicySearchRequest(cells[1], cells[2],
                            OffsetDateTime.parse(cells[3]), cells[4]), headers), JsonNode.class);
            assertEquals(200, response.getStatusCode().value(), cells[0]);
            JsonNode body = response.getBody();
            assertNotNull(body, cells[0]);
            Set<String> actualIds = new TreeSet<>();
            Set<String> actualRefs = new TreeSet<>();
            body.path("evidence").forEach(item -> {
                actualIds.add(item.path("policyId").asText());
                actualRefs.add(item.path("sourceRef").asText());
            });
            boolean pass = expected.equals(body.path("outcome").asText())
                    && actualIds.equals(values(cells[6])) && actualRefs.equals(values(cells[7]));
            if (pass) {
                passed++;
            }
            report.append(String.join("\t", cells[0], expected, body.path("outcome").asText(),
                    cells[6], join(actualIds), cells[7], join(actualRefs), pass ? "通过" : "失败")).append('\n');
        }
        report.append("# 逐例通过=").append(passed).append('/').append(lines.size() - 1).append('\n');
        Path output = Path.of("target/eval/policy-rag-holdout-v1.tsv");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        assertEquals(lines.size() - 1, passed, "逐例结果见 " + output);
    }

    private static Set<String> values(String cell) {
        return cell.equals("-") ? Set.of() : new TreeSet<>(Arrays.asList(cell.split("\\|")));
    }

    private static String join(Set<String> values) {
        return values.isEmpty() ? "-" : values.stream().collect(Collectors.joining("|"));
    }

    private record PolicySearchRequest(String storeId, String modelCode,
                                       OffsetDateTime appointmentAt, String question) {
    }
}
