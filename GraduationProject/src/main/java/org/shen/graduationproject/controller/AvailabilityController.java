package org.shen.graduationproject.controller;

import java.time.OffsetDateTime;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.application.*;
import org.shen.graduationproject.domain.AvailabilityCommands.*;
import org.shen.graduationproject.infrastructure.CatalogIdempotency;
import org.shen.platform.contracts.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端维护和普通用户查询共用服务端权限、时间及来源校验。
 * GET 日历仅查询区间，POST 搜索也不写入占用；管理写操作必须携带幂等键。
 * 控制器不接收自报操作人，所有调用均使用安全链注入的 AuthActor。
 */
@RestController
@Profile("business")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AvailabilityController {
    private final AvailabilityQueries queries;
    private final AvailabilityMaintenance maintenance;
    private final BookingPolicies policies;

    private ApiResponse<?> ok(Object data) {
        return ApiResponse.success(data, MDC.get(TraceIds.MDC_KEY));
    }

    private ResponseEntity<?> result(CatalogIdempotency.Result outcome) {
        return ResponseEntity.status(outcome.status()).header("Idempotency-Replayed", Boolean.toString(outcome.replayed())).body(ok(outcome.data()));
    }

    /**
     * 时间参数要求带偏移量；应用层限制最大窗口并过滤不可见资源及敏感活动信息。
     */
    @GetMapping("/resources/{id}/calendar")
    ApiResponse<?> calendar(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ok(queries.calendar(actor, id, from, to));
    }

    /**
     * 只读组合搜索，POST 用于传递复杂条件，不表示创建预约或暂占。
     */
    @PostMapping("/availability/search")
    ApiResponse<?> search(@AuthenticationPrincipal AuthActor actor, @Valid @RequestBody Search input) {
        return ok(queries.search(actor, input));
    }

    /**
     * 用资源预期版本一次替换整套开放规则，规则列表为空时表示清空。
     */
    @PutMapping("/admin/resources/{id}/opening-rules")
    ResponseEntity<?> opening(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                              @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody OpeningRules input) {
        return result(maintenance.opening(actor, key, id, input));
    }

    /**
     * 管理范围内分页读取外部占用维护信息，权限要求高于普通日历展示。
     */
    @GetMapping("/admin/resources/{id}/external-occupancies")
    ApiResponse<?> occupancies(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                               @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(maintenance.list(actor, id, page, size));
    }

    /**
     * 手工登记来源占用，但不生成完整空闲或覆盖证明。
     */
    @PostMapping("/admin/resources/{id}/external-occupancies")
    ResponseEntity<?> occupancy(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                                @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Occupancy input) {
        return result(maintenance.create(actor, key, id, input));
    }

    /**
     * 带版本更新或停用占用记录，历史通过状态与审计保留。
     */
    @PatchMapping("/admin/external-occupancies/{id}")
    ResponseEntity<?> patch(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody OccupancyUpdate input) {
        return result(maintenance.patch(actor, key, id, input));
    }

    /**
     * 读取指定数据模式下的生效策略；无配置时明确返回未配置状态。
     */
    @GetMapping("/admin/policies/{scope}")
    ApiResponse<?> policy(@AuthenticationPrincipal AuthActor actor, @PathVariable String scope) {
        return ok(policies.get(actor, scope));
    }

    @GetMapping("/booking-rules")
    ApiResponse<?> rules(@AuthenticationPrincipal AuthActor actor,@RequestParam String dataMode) {
        return ok(policies.publicRules(actor,dataMode));
    }

    /**
     * 仅系统管理员可以带制度依据和当前版本创建新策略版本。
     */
    @PostMapping("/admin/policies/{scope}/versions")
    ResponseEntity<?> policyVersion(@AuthenticationPrincipal AuthActor actor, @PathVariable String scope,
                                    @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PolicyVersion input) {
        return result(policies.create(actor, key, scope, input));
    }
}
