package com.zz.agent.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyMetadataFilterTest {

    private final PolicyMetadataFilter filter = new PolicyMetadataFilter();
    private List<PolicySource> policies;

    @BeforeEach
    void loadPolicies() throws IOException {
        policies = new PolicySourceLoader().load(Path.of("data/policies"));
    }

    @Test
    void matchesExactModelCodeAndKeepsSourceReference() {
        var result = filter.find(policies, "STORE-A", "MX-7", at("2026-11-07T14:00:00+08:00"));

        assertEquals(PolicyMetadataFilter.Outcome.SINGLE_MATCH, result.outcome());
        assertEquals(List.of("POL-STORE-A-MX-7-TEST"),
                result.policies().stream().map(PolicySource::policyId).toList());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1",
                result.policies().getFirst().sourceRef());
    }

    @Test
    void excludesExpiredPolicyEvenWhenItsStatusIsActive() {
        var result = filter.find(policies, "STORE-D", "MX-7", at("2026-11-08T14:00:00+08:00"));

        assertEquals(PolicyMetadataFilter.Outcome.NO_MATCH, result.outcome());
        assertTrue(result.policies().isEmpty());
    }

    @Test
    void excludesPolicyAtTheExactEffectiveEnd() {
        var result = filter.find(policies, "STORE-D", "MX-7", at("2026-10-20T00:00:00+08:00"));

        assertEquals(PolicyMetadataFilter.Outcome.NO_MATCH, result.outcome());
    }

    @Test
    void exposesAllOverlappingPoliciesInsteadOfChoosingOne() {
        var result = filter.find(policies, "STORE-E", "MX-7", at("2026-11-07T14:00:00+08:00"));

        assertEquals(PolicyMetadataFilter.Outcome.MULTIPLE_MATCHES, result.outcome());
        assertEquals(List.of("POL-STORE-E-MX-7-ALLOW", "POL-STORE-E-MX-7-DENY"),
                result.policies().stream().map(PolicySource::policyId).toList());
        assertEquals(List.of(
                "data/policies/STORE-E-MX-7-allow-v1.md#rule-1",
                "data/policies/STORE-E-MX-7-deny-v1.md"),
                result.policies().stream().map(PolicySource::sourceRef).toList());
    }

    @Test
    void excludesPolicyThatIsNotActive() {
        PolicySource source = policies.getFirst();
        PolicySource withdrawn = new PolicySource(
                source.policyId(), source.version(), source.title(), source.simulated(),
                source.storeId(), source.modelCode(), source.ruleType(),
                source.effectiveFrom(), source.effectiveTo(), "WITHDRAWN", source.sourceRef(), source.body());

        var result = filter.find(List.of(withdrawn), "STORE-A", "MX-7", at("2026-11-07T14:00:00+08:00"));

        assertEquals(PolicyMetadataFilter.Outcome.NO_MATCH, result.outcome());
    }

    private static OffsetDateTime at(String value) {
        return OffsetDateTime.parse(value);
    }
}
