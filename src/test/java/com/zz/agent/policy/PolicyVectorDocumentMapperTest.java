package com.zz.agent.policy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyVectorDocumentMapperTest {

    @Test
    void preservesPolicyIdentityScopeAndCitationAcrossRepeatedMapping() throws Exception {
        List<PolicySource> sources = new PolicySourceLoader().load(Path.of("data/policies"));
        PolicySource testDrive = source(sources, "POL-STORE-A-MX-7-TEST");
        PolicySource noHeading = source(sources, "POL-STORE-E-MX-7-DENY");
        PolicyVectorDocumentMapper mapper = new PolicyVectorDocumentMapper();

        List<Document> first = mapper.map(List.of(testDrive, noHeading));
        List<Document> again = mapper.map(List.of(testDrive, noHeading));

        assertEquals(2, first.size());
        Document rule = first.getFirst();
        UUID.fromString(rule.getId());
        assertEquals(rule.getId(), again.getFirst().getId());
        assertEquals("POL-STORE-A-MX-7-TEST@1#chunk-1", rule.getMetadata().get("chunkId"));
        assertEquals("POL-STORE-A-MX-7-TEST", rule.getMetadata().get("policyId"));
        assertEquals(1, rule.getMetadata().get("version"));
        assertEquals("STORE-A", rule.getMetadata().get("storeId"));
        assertEquals("MX-7", rule.getMetadata().get("modelCode"));
        assertEquals("TEST_DRIVE", rule.getMetadata().get("ruleType"));
        assertEquals("ACTIVE", rule.getMetadata().get("status"));
        assertEquals(true, rule.getMetadata().get("simulated"));
        assertEquals(testDrive.effectiveFrom().toEpochSecond(), rule.getMetadata().get("effectiveFromEpoch"));
        assertEquals(testDrive.effectiveTo().toEpochSecond(), rule.getMetadata().get("effectiveToEpoch"));
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                rule.getMetadata().get("sourceRef"));
        assertTrue(rule.getText().contains("试驾"));

        Document plain = first.getLast();
        assertEquals("data/policies/STORE-E-MX-7-deny-v1.md", plain.getMetadata().get("sourceRef"));
        assertTrue(plain.getText().contains("暂停接受"));

        PolicySource revised = new PolicySource(testDrive.policyId(), 2, testDrive.title(),
                testDrive.simulated(), testDrive.storeId(), testDrive.modelCode(), testDrive.ruleType(),
                testDrive.effectiveFrom(), testDrive.effectiveTo(), testDrive.status(),
                testDrive.sourceRef(), testDrive.body());
        assertNotEquals(rule.getId(), mapper.map(List.of(revised)).getFirst().getId());
    }

    private static PolicySource source(List<PolicySource> sources, String id) {
        return sources.stream().filter(source -> source.policyId().equals(id)).findFirst().orElseThrow();
    }
}
