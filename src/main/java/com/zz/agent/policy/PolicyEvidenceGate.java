package com.zz.agent.policy;

import org.springframework.ai.document.Document;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative guard for the current simulated test-drive corpus.
 * CANDIDATES means only that traceable policy chunks may be shown to a later answer step;
 * it does not prove a generated answer or a real booking claim.
 */
public final class PolicyEvidenceGate {

    private static final Pattern PRICE_QUESTION =
            Pattern.compile("价格|优惠|报价|折扣|多少钱|费用|收费|车价|落地价");
    private static final Pattern LIVE_AVAILABILITY_QUESTION =
            Pattern.compile("现车|有车|有试驾车|试驾车.*(有|可用|在)|空闲时段|剩余时段|可用时段|车源|库存|名额|预约成功|约到");
    private static final Pattern OTHER_UNSUPPORTED_TOPIC =
            Pattern.compile("证件|身份证|驾驶证|驾照|年龄|押金|保险|贷款|金融|违约|合同");
    private static final Pattern TRIAL_QUESTION =
            Pattern.compile("试驾|试车|预约|能约|可约|约车|约试驾|申请时间|允许申请|能申请|可申请|接受申请");

    public enum Outcome {
        NO_APPLICABLE_POLICY,
        NO_EVIDENCE,
        CANDIDATES,
        REVIEW_REQUIRED
    }

    public record Evidence(String chunkId, String policyId, int version, String sourceRef, String text) {
    }

    public record Decision(Outcome outcome, List<Evidence> evidence) {
        public Decision {
            evidence = List.copyOf(evidence);
        }
    }

    private final PolicyMetadataFilter metadataFilter = new PolicyMetadataFilter();
    private final PolicyVectorDocumentMapper documentMapper = new PolicyVectorDocumentMapper();

    public Decision evaluate(List<PolicySource> policies, String storeId, String modelCode,
                             OffsetDateTime appointmentAt, String question, List<Document> retrieved) {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(retrieved, "retrieved");
        PolicyMetadataFilter.Result applicable =
                metadataFilter.find(policies, storeId, modelCode, appointmentAt);
        if (applicable.outcome() == PolicyMetadataFilter.Outcome.NO_MATCH) {
            return new Decision(Outcome.NO_APPLICABLE_POLICY, List.of());
        }
        if (PRICE_QUESTION.matcher(question).find()
                || LIVE_AVAILABILITY_QUESTION.matcher(question).find()
                || OTHER_UNSUPPORTED_TOPIC.matcher(question).find()) {
            return new Decision(Outcome.NO_EVIDENCE, List.of());
        }
        if (!TRIAL_QUESTION.matcher(question).find()) {
            return new Decision(Outcome.NO_EVIDENCE, List.of());
        }

        if (applicable.outcome() == PolicyMetadataFilter.Outcome.MULTIPLE_MATCHES) {
            List<Evidence> allApplicable = documentMapper.map(applicable.policies()).stream()
                    .map(PolicyEvidenceGate::toEvidence).toList();
            return new Decision(Outcome.REVIEW_REQUIRED, allApplicable);
        }

        Map<String, Document> canonical = new HashMap<>();
        for (Document document : documentMapper.map(applicable.policies())) {
            canonical.put(document.getId(), document);
        }
        List<Evidence> evidence = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Document candidate : retrieved) {
            Document original = canonical.get(candidate.getId());
            if (original != null && original.getText().equals(candidate.getText())
                    && sameMetadata(original.getMetadata(), candidate.getMetadata())
                    && seen.add(candidate.getId())) {
                evidence.add(toEvidence(original));
            }
        }
        return new Decision(evidence.isEmpty() ? Outcome.NO_EVIDENCE : Outcome.CANDIDATES, evidence);
    }

    private static Evidence toEvidence(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new Evidence((String) metadata.get("chunkId"), (String) metadata.get("policyId"),
                (Integer) metadata.get("version"), (String) metadata.get("sourceRef"), document.getText());
    }

    private static boolean sameMetadata(Map<String, Object> original, Map<String, Object> candidate) {
        for (String key : List.of("chunkId", "policyId", "sourceRef", "storeId", "modelCode",
                "ruleType", "status", "simulated")) {
            if (!Objects.equals(original.get(key), candidate.get(key))) {
                return false;
            }
        }
        for (String key : List.of("version", "effectiveFromEpoch", "effectiveToEpoch")) {
            if (!(original.get(key) instanceof Number expected) || !(candidate.get(key) instanceof Number actual)
                    || new BigDecimal(expected.toString()).compareTo(new BigDecimal(actual.toString())) != 0) {
                return false;
            }
        }
        return true;
    }
}
