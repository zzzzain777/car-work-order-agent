package com.zz.agent.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicySourceLoaderTest {

    private final PolicySourceLoader loader = new PolicySourceLoader();

    @Test
    void loadsTheSixSimulatedPoliciesWithoutTemplates() throws IOException {
        List<PolicySource> sources = loader.load(Path.of("data/policies"));

        assertEquals(6, sources.size());
        assertEquals(List.of(
                "POL-STORE-A-MX-7-TEST",
                "POL-STORE-A-MX-70-TEST",
                "POL-STORE-C-MX-7-TEST",
                "POL-STORE-D-MX-7-TEST",
                "POL-STORE-E-MX-7-ALLOW",
                "POL-STORE-E-MX-7-DENY"
        ), sources.stream().map(PolicySource::policyId).toList());

        PolicySource mx70 = sources.get(1);
        assertEquals("MX-70", mx70.modelCode());
        assertEquals("STORE-A", mx70.storeId());
        assertEquals("data/policies/STORE-A-MX-70-test-drive-v1.md#rule-1", mx70.sourceRef());
        assertEquals(OffsetDateTime.parse("2026-12-01T00:00:00+08:00"), mx70.effectiveTo());
        assertTrue(mx70.body().contains("仅限每日 09:00"));
    }

    @Test
    void keepsPolicyBodyWhenItHasNoRuleHeading() throws IOException {
        List<PolicySource> sources = loader.load(Path.of("data/policies"));
        PolicySource deny = sources.stream()
                .filter(source -> source.policyId().equals("POL-STORE-E-MX-7-DENY"))
                .findFirst().orElseThrow();

        assertTrue(deny.body().contains("暂停接受 `MX-7` 试驾申请"));
        assertEquals("data/policies/STORE-E-MX-7-deny-v1.md", deny.sourceRef());
    }

    @Test
    void rejectsPolicyThatIsNotMarkedSimulated(@TempDir Path directory) throws IOException {
        String source = Files.readString(Path.of("data/policies/STORE-A-MX-7-test-drive-v1.md"));
        Files.writeString(directory.resolve("invalid.md"), source.replace("isSimulated: true", "isSimulated: false"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> loader.load(directory));
        assertTrue(error.getMessage().contains("simulated"));
    }

    @Test
    void rejectsAnEmptyEffectivePeriod(@TempDir Path directory) throws IOException {
        String source = Files.readString(Path.of("data/policies/STORE-A-MX-7-test-drive-v1.md"));
        Files.writeString(directory.resolve("invalid.md"),
                source.replace("2026-12-01T00:00:00+08:00", "2026-10-01T00:00:00+08:00"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> loader.load(directory));
        assertTrue(error.getMessage().contains("effective period"));
    }

    @Test
    void rejectsDuplicatePolicyVersions(@TempDir Path directory) throws IOException {
        String source = Files.readString(Path.of("data/policies/STORE-A-MX-7-test-drive-v1.md"));
        Files.writeString(directory.resolve("first.md"),
                source.replace("STORE-A-MX-7-test-drive-v1.md", "first.md"));
        Files.writeString(directory.resolve("second.md"),
                source.replace("STORE-A-MX-7-test-drive-v1.md", "second.md"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> loader.load(directory));
        assertTrue(error.getMessage().contains("Duplicate policy version"));
    }

    @Test
    void rejectsCitationPointingToAnotherFile(@TempDir Path directory) throws IOException {
        String source = Files.readString(Path.of("data/policies/STORE-A-MX-7-test-drive-v1.md"));
        Files.writeString(directory.resolve("wrong.md"), source);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> loader.load(directory));
        assertTrue(error.getMessage().contains("sourceRef"));
    }
}
