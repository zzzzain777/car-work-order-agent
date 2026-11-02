package com.zz.agent.store;

import com.zz.agent.integration.CarSystemReadClient;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 管理员演示页的模拟门店只读查询；此接口不保存选择。 */
@RestController
@Profile("policy-rag & deepseek & bailian-embedding")
@RequestMapping("/api/agent/nearby-stores")
public final class NearbyStoreController {

    public record SearchInput(Double latitude, Double longitude, String modelCode,
                              OffsetDateTime appointmentAt) {
    }

    private final NearbyStoreAvailabilityService service;
    private final CarSystemReadClient carSystem;

    public NearbyStoreController(NearbyStoreAvailabilityService service, CarSystemReadClient carSystem) {
        this.service = Objects.requireNonNull(service, "附近门店查询不能为空");
        this.carSystem = Objects.requireNonNull(carSystem, "业务系统只读客户端不能为空");
    }

    @PostMapping
    public ResponseEntity<List<NearbyStoreAvailabilityService.Option>> search(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader,
            @RequestBody SearchInput input) {
        CarSystemReadClient.Identity identity = carSystem.currentUser(cookieHeader);
        if (identity == null || identity.id() <= 0 || identity.userRole() != 2 || identity.userStatus() != 0) {
            throw new SecurityException("需要有效管理员会话");
        }
        if (input == null || input.latitude() == null || input.longitude() == null) {
            throw new IllegalArgumentException("请提供客户位置");
        }
        List<NearbyStoreAvailabilityService.Option> options = service.findNearestFive(
                new GeoPoint(input.latitude(), input.longitude()), input.modelCode(), input.appointmentAt());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(options);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden(SecurityException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", "需要有效管理员会话"));
    }

    @ExceptionHandler(CarSystemReadClient.ReadException.class)
    public ResponseEntity<Map<String, String>> readFailed(CarSystemReadClient.ReadException error) {
        HttpStatus status = error.kind() == CarSystemReadClient.ReadException.Kind.AUTH_REQUIRED
                ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", status == HttpStatus.UNAUTHORIZED
                        ? "管理员会话失效" : "管理员身份读取失败"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("message", "请检查位置、车型和计划试驾时间"));
    }
}
