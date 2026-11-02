package com.zz.agent.workflow;

import com.zz.agent.integration.CarSystemReadClient;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 本机演示入口；确认只调用模拟端，绝不提供真实分配能力。 */
@RestController
@Profile("policy-rag & deepseek & bailian-embedding")
@RequestMapping("/api/agent/tasks")
public final class AgentTaskController {

    public record CreateInput(long workOrderId) {
    }

    public record SupplementInput(long expectedVersion, String storeId, String modelCode,
                                  OffsetDateTime appointmentAt) {
    }

    public record RecommendInput(long expectedVersion) {
    }

    public record DecisionInput(String proposalId, long version, boolean approve) {
    }

    public record RecommendationOutput(JdbcAgentTaskStore.Task task, ReadOnlyAdviceFlow.Result preview,
                                       StructuredAdviceService.Advice advice,
                                       JdbcAdviceRunStore.Run run,
                                       JdbcConfirmationStore.Proposal proposal, boolean simulated) {
    }

    public record DecisionOutput(TaskState state, UUID attemptId, String observation, boolean simulated) {
    }

    private final AgentTaskWorkflow workflow;
    private final AgentAdviceOrchestrator orchestrator;
    private final JdbcAdviceRunStore runs;
    private final SimulatedConfirmationFlow confirmations;
    private final AgentTaskCsrf csrf;
    private final CarSystemReadClient carSystem;

    public AgentTaskController(AgentTaskWorkflow workflow, AgentAdviceOrchestrator orchestrator,
                               JdbcAdviceRunStore runs, SimulatedConfirmationFlow confirmations, AgentTaskCsrf csrf,
                               CarSystemReadClient carSystem) {
        this.workflow = Objects.requireNonNull(workflow, "任务流程不能为空");
        this.orchestrator = Objects.requireNonNull(orchestrator, "建议流程不能为空");
        this.runs = Objects.requireNonNull(runs, "建议运行记录不能为空");
        this.confirmations = Objects.requireNonNull(confirmations, "模拟确认流程不能为空");
        this.csrf = Objects.requireNonNull(csrf, "令牌服务不能为空");
        this.carSystem = Objects.requireNonNull(carSystem, "业务系统只读客户端不能为空");
    }

    @GetMapping("/csrf")
    public ResponseEntity<AgentTaskCsrf.Issued> csrfToken(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少管理员登录会话");
        }
        CarSystemReadClient.Identity identity = carSystem.currentUser(cookieHeader);
        if (identity == null || identity.id() <= 0 || identity.userRole() != 2 || identity.userStatus() != 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员登录");
        }
        try {
            return noStore(csrf.issue(cookieHeader));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少有效管理员会话");
        }
    }

    @PostMapping
    public ResponseEntity<JdbcAgentTaskStore.Task> create(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @RequestBody CreateInput input) {
        if (input == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供工单编号");
        }
        return noStore(workflow.open(cookieHeader, input.workOrderId()));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<JdbcAgentTaskStore.Task> find(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @PathVariable UUID taskId) {
        return noStore(workflow.find(cookieHeader, taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在")));
    }

    @GetMapping("/{taskId}/runs")
    public ResponseEntity<List<JdbcAdviceRunStore.Run>> runs(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @PathVariable UUID taskId) {
        JdbcAgentTaskStore.Task task = workflow.find(cookieHeader, taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
        return noStore(runs.recent(task.id(), task.adminId()));
    }

    @GetMapping("/{taskId}/pending-proposal")
    public ResponseEntity<JdbcConfirmationStore.Proposal> pendingProposal(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @PathVariable UUID taskId) {
        return noStore(confirmations.pending(cookieHeader, taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有有效的待确认提案")));
    }

    @PostMapping("/{taskId}/supplement")
    public ResponseEntity<JdbcAgentTaskStore.Task> supplement(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @PathVariable UUID taskId, @RequestBody SupplementInput input) {
        if (input == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供补充信息");
        }
        ReadOnlyAdviceFlow.Supplement supplement = new ReadOnlyAdviceFlow.Supplement(
                input.storeId(), input.modelCode(), input.appointmentAt());
        return noStore(workflow.supplement(cookieHeader, taskId, input.expectedVersion(), supplement)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "任务版本已变化")));
    }

    @PostMapping("/{taskId}/recommend")
    public ResponseEntity<RecommendationOutput> recommend(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @PathVariable UUID taskId, @RequestBody RecommendInput input) {
        if (input == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供任务版本");
        }
        long startedAt = System.nanoTime();
        AgentAdviceOrchestrator.Recommendation recommendation = orchestrator
                .refreshAndSuggest(cookieHeader, taskId, input.expectedVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "任务版本已变化"));
        long elapsedMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        JdbcAdviceRunStore.Run run = runs.record(recommendation.task(), recommendation.advice(), elapsedMs)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "建议运行期间任务版本已变化"));
        JdbcConfirmationStore.Proposal proposal = null;
        if (recommendation.advice().outcome() == StructuredAdviceService.Outcome.CANDIDATE) {
            proposal = confirmations.publish(cookieHeader, recommendation)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "证据或任务版本已变化"));
        }
        JdbcAgentTaskStore.Task current = proposal == null ? recommendation.task()
                : workflow.find(cookieHeader, taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "提案已变化"));
        return noStore(new RecommendationOutput(current, recommendation.preview(),
                recommendation.advice(), run, proposal, true));
    }

    @PostMapping("/{taskId}/decision")
    public ResponseEntity<DecisionOutput> decide(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @PathVariable UUID taskId, @RequestBody DecisionInput input) {
        if (input == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供确认决定");
        }
        SimulatedConfirmationFlow.Decision decision = confirmations.confirm(cookieHeader, taskId,
                new ConfirmationCommand(input.proposalId(), input.version(), input.approve()));
        DecisionOutput output = new DecisionOutput(decision.state(), decision.attemptId(),
                decision.observation(), true);
        return ResponseEntity.status(decision.state() == TaskState.STALE_PROPOSAL
                        ? HttpStatus.CONFLICT : HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(output);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden(SecurityException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", "管理员登录态无效或无权访问此任务"));
    }

    @ExceptionHandler(CarSystemReadClient.ReadException.class)
    public ResponseEntity<Map<String, String>> businessReadFailed(CarSystemReadClient.ReadException error) {
        HttpStatus status = error.kind() == CarSystemReadClient.ReadException.Kind.AUTH_REQUIRED
                ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", status == HttpStatus.UNAUTHORIZED
                        ? "管理员登录态无效" : "业务系统读取失败，请稍后核对"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", "输入不合法，请核对工单和补充信息"));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
