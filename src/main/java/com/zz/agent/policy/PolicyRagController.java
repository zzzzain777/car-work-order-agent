package com.zz.agent.policy;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

/** 本地只读演示接口；所有返回的政策均为模拟资料。 */
@RestController
@Profile("policy-rag")
@RequestMapping("/api/policies")
public final class PolicyRagController {

    public record SearchInput(String storeId, String modelCode, OffsetDateTime appointmentAt, String question) {
    }

    public record SearchOutput(boolean simulated, String outcome, String message,
                               List<PolicyEvidenceGate.Evidence> evidence) {
    }

    private final PolicyRagService service;

    public PolicyRagController(PolicyRagService service) {
        this.service = service;
    }

    @PostMapping("/search")
    public SearchOutput search(@RequestBody SearchInput input) throws IOException {
        if (input == null || blank(input.storeId()) || blank(input.modelCode())
                || input.appointmentAt() == null || blank(input.question())
                || input.storeId().length() > 100 || input.modelCode().length() > 100
                || input.question().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供门店编号、车型编号、准确时间和问题");
        }
        PolicyEvidenceGate.Decision decision = service.search(input.storeId(), input.modelCode(),
                input.appointmentAt(), input.question());
        String message = switch (decision.outcome()) {
            case CANDIDATES -> "找到模拟政策片段，请核对原文；不代表有车、预约成功或允许分配";
            case REVIEW_REQUIRED -> "同范围存在多份有效政策，需人工处理";
            case NO_APPLICABLE_POLICY -> "没有适用的模拟政策";
            case NO_EVIDENCE -> "没有足够政策依据";
        };
        return new SearchOutput(true, decision.outcome().name(), message, decision.evidence());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
