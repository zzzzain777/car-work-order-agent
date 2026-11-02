package com.zz.agent.store;

import com.zz.agent.integration.CarSystemReadClient;
import com.zz.agent.workflow.AgentTaskCsrf;
import com.zz.agent.workflow.JdbcAgentTaskStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 客户本人选取模拟门店；所有选择只进入 Agent 任务。 */
@RestController
@Profile("policy-rag & deepseek & bailian-embedding")
@RequestMapping("/api/agent/customer-stores")
public final class CustomerStoreController {

    public record OfferInput(long expectedVersion, Double latitude, Double longitude,
                             String modelCode, OffsetDateTime appointmentAt) {
    }

    public record ChoiceInput(UUID offerId, String storeId) {
    }

    private final CustomerStoreSelectionService flow;
    private final AgentTaskCsrf csrf;

    public CustomerStoreController(CustomerStoreSelectionService flow, AgentTaskCsrf csrf) {
        this.flow = Objects.requireNonNull(flow, "客户选店流程不能为空");
        this.csrf = Objects.requireNonNull(csrf, "令牌服务不能为空");
    }

    @GetMapping("/csrf")
    public ResponseEntity<AgentTaskCsrf.Issued> csrf(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader) {
        flow.requireCustomer(cookieHeader);
        return noStore(csrf.issue(cookieHeader));
    }

    @PostMapping("/{taskId}/offers")
    public ResponseEntity<JdbcAgentTaskStore.StoreOffer> offer(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @PathVariable UUID taskId, @RequestBody OfferInput input) {
        if (input == null || input.latitude() == null || input.longitude() == null) {
            throw new IllegalArgumentException("请提供位置和任务版本");
        }
        return noStore(flow.offer(cookieHeader, taskId, input.expectedVersion(),
                new GeoPoint(input.latitude(), input.longitude()), input.modelCode(), input.appointmentAt()));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<CustomerStoreSelectionService.CustomerTaskView> view(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @PathVariable UUID taskId) {
        return noStore(flow.view(cookieHeader, taskId));
    }

    @PostMapping("/choices")
    public ResponseEntity<JdbcAgentTaskStore.Task> choose(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @RequestBody ChoiceInput input) {
        if (input == null || input.offerId() == null || input.storeId() == null) {
            throw new IllegalArgumentException("请选择本次候选中的门店");
        }
        JdbcAgentTaskStore.Task task = flow.choose(cookieHeader, input.offerId(), input.storeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "门店状态、工单或任务已变化，请重新获取候选"));
        return noStore(task);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden(SecurityException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", "客户会话无效或无权选择此工单门店"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", "任务信息已变化，请重新获取候选"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", "请检查任务、位置、车型和试驾时间"));
    }

    @ExceptionHandler(CarSystemReadClient.ReadException.class)
    public ResponseEntity<Map<String, String>> readFailed(CarSystemReadClient.ReadException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", "业务系统读取失败，请稍后核对"));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
