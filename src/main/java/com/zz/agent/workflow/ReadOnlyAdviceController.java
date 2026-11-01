package com.zz.agent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** 仅限本机的只读预览入口；不创建任务，也不提供分配操作。 */
@RestController
@Profile("policy-rag")
public final class ReadOnlyAdviceController {

    private final ReadOnlyAdviceFlow flow;

    public ReadOnlyAdviceController(ReadOnlyAdviceFlow flow) {
        this.flow = flow;
    }

    @GetMapping("/api/agent/preview")
    public ResponseEntity<ReadOnlyAdviceFlow.Result> preview(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @RequestParam long workOrderId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String appointmentAt) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少管理员登录会话");
        }
        if (workOrderId <= 0 || invalidCode(storeId) || invalidCode(modelCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单、门店或车型编号不合法");
        }
        OffsetDateTime time = null;
        if (appointmentAt != null && !appointmentAt.isBlank()) {
            try {
                time = OffsetDateTime.parse(appointmentAt);
            } catch (DateTimeParseException error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供带时区的准确试驾时间");
            }
        }
        ReadOnlyAdviceFlow.Result result = flow.preview(workOrderId, cookieHeader,
                new ReadOnlyAdviceFlow.Supplement(storeId, modelCode, time));
        HttpStatus status = result.stage() == ReadOnlyAdviceFlow.Stage.UNAUTHORIZED
                ? HttpStatus.UNAUTHORIZED : HttpStatus.OK;
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(result);
    }

    private static boolean invalidCode(String value) {
        return value != null && !value.isBlank() && (value.length() > 100 || !value.matches("[A-Za-z0-9-]+"));
    }
}
