package com.zz.agent.policy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRuleChunkerTest {

    private final PolicyRuleChunker chunker = new PolicyRuleChunker();

    @Test
    void keepsSharedConditionsWithEachRuleAndPointsToTheRightHeading() throws IOException {
        PolicySource original = policy("POL-STORE-A-MX-7-TEST");
        PolicySource withTwoRules = withBody(original, """
                # 模拟规则

                两条规则都要求先确认门店。

                ## rule-1

                周一可试驾。

                ## rule-2

                周二可试驾。
                """);

        List<PolicyRuleChunker.Chunk> chunks = chunker.split(withTwoRules);

        assertEquals(2, chunks.size());
        assertEquals("POL-STORE-A-MX-7-TEST@1#chunk-1", chunks.get(0).id());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1", chunks.get(0).sourceRef());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-2", chunks.get(1).sourceRef());
        assertTrue(chunks.get(0).text().contains("先确认门店"));
        assertTrue(chunks.get(1).text().contains("先确认门店"));
        assertTrue(chunks.get(0).text().contains("周一可试驾"));
        assertFalse(chunks.get(0).text().contains("周二可试驾"));
        assertTrue(chunks.get(1).text().contains("周二可试驾"));
    }

    @Test
    void retainsTheWholeDocumentWhenItHasNoRuleHeading() throws IOException {
        PolicySource policy = policy("POL-STORE-E-MX-7-DENY");

        List<PolicyRuleChunker.Chunk> chunks = chunker.split(policy);

        assertEquals(1, chunks.size());
        assertEquals("data/policies/STORE-E-MX-7-deny-v1.md", chunks.getFirst().sourceRef());
        assertTrue(chunks.getFirst().text().contains("暂停接受 `MX-7` 试驾申请"));
    }

    private static PolicySource policy(String id) throws IOException {
        return new PolicySourceLoader().load(Path.of("data/policies")).stream()
                .filter(source -> source.policyId().equals(id))
                .findFirst().orElseThrow();
    }

    private static PolicySource withBody(PolicySource source, String body) {
        return new PolicySource(
                source.policyId(), source.version(), source.title(), source.simulated(),
                source.storeId(), source.modelCode(), source.ruleType(),
                source.effectiveFrom(), source.effectiveTo(), source.status(), source.sourceRef(), body);
    }
}
