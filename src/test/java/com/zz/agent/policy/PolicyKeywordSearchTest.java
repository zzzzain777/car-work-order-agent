package com.zz.agent.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyKeywordSearchTest {

    private final PolicyKeywordSearch search = new PolicyKeywordSearch();
    private List<PolicySource> policies;

    @BeforeEach
    void loadPolicies() throws IOException {
        policies = new PolicySourceLoader().load(Path.of("data/policies"));
    }

    @Test
    void findsTheExactModelRuleWithItsOriginalTextAndReference() {
        var result = search.find(policies, "STORE-A", "MX-7", at("2026-11-07T14:00:00+08:00"),
                "MX-7 试驾时间要求");

        assertEquals(PolicyKeywordSearch.Status.MATCHES, result.status());
        assertEquals(1, result.hits().size());
        var hit = result.hits().getFirst();
        assertEquals("POL-STORE-A-MX-7-TEST", hit.policyId());
        assertEquals(1, hit.version());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-1", hit.sourceRef());
        assertTrue(hit.text().contains("10:00（含）至 17:00（不含）"));
        assertTrue(hit.score() > 0);
    }

    @Test
    void rejectsExpiredPolicyBeforeSearchingItsText() {
        var result = search.find(policies, "STORE-D", "MX-7", at("2026-11-08T14:00:00+08:00"),
                "试驾申请时间");

        assertEquals(PolicyKeywordSearch.Status.NO_APPLICABLE_POLICY, result.status());
        assertTrue(result.hits().isEmpty());
    }

    @Test
    void returnsBothApplicablePoliciesForReviewEvenIfOneRanksLower() {
        var result = search.find(policies, "STORE-E", "MX-7", at("2026-11-07T14:00:00+08:00"),
                "允许申请");

        assertEquals(PolicyKeywordSearch.Status.REVIEW_REQUIRED, result.status());
        assertEquals(Set.of("POL-STORE-E-MX-7-ALLOW", "POL-STORE-E-MX-7-DENY"),
                result.hits().stream().map(PolicyKeywordSearch.Hit::policyId).collect(Collectors.toSet()));
        assertTrue(result.hits().stream().anyMatch(hit ->
                hit.sourceRef().equals("data/policies/STORE-E-MX-7-deny-v1.md")
                        && hit.text().contains("暂停接受")));
    }

    @Test
    void reportsNoKeywordMatchInsteadOfInventingAnAnswer() {
        var result = search.find(policies, "STORE-A", "MX-7", at("2026-11-07T14:00:00+08:00"),
                "优惠价格");

        assertEquals(PolicyKeywordSearch.Status.NO_KEYWORD_MATCH, result.status());
        assertTrue(result.hits().isEmpty());
    }

    @Test
    void ranksTheRuleWithMoreMatchingKeywordsFirst() {
        PolicySource source = policies.getFirst();
        PolicySource withTwoRules = new PolicySource(
                source.policyId(), source.version(), source.title(), source.simulated(),
                source.storeId(), source.modelCode(), source.ruleType(),
                source.effectiveFrom(), source.effectiveTo(), source.status(), source.sourceRef(), """
                ## rule-1

                门店营业时间为上午。

                ## rule-2

                试驾时间为下午。
                """);

        var result = search.find(List.of(withTwoRules), "STORE-A", "MX-7",
                at("2026-11-07T14:00:00+08:00"), "试驾时间");

        assertEquals(PolicyKeywordSearch.Status.MATCHES, result.status());
        assertEquals("data/policies/STORE-A-MX-7-test-drive-v1.md#rule-2",
                result.hits().getFirst().sourceRef());
        assertEquals(2, result.hits().size());
        assertTrue(result.hits().getFirst().score() > result.hits().get(1).score());
    }

    private static OffsetDateTime at(String value) {
        return OffsetDateTime.parse(value);
    }
}
