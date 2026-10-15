package org.shen.graduationproject.controller;

import java.util.Map;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.application.Applications;
import org.shen.graduationproject.domain.ApplicationCommands.*;
import org.shen.graduationproject.infrastructure.CatalogIdempotency;
import org.shen.platform.contracts.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 普通申请与管理审批共用用例；控制器不接收 applicantId、operatorId 或客户端角色。
 */
@RestController
@Profile("business")
@RequiredArgsConstructor
class ApplicationsController {
    private final Applications applications;

    /**
     * 申请包含本人资料和审批理由，读接口也禁止浏览器及中间代理保存响应。
     */
    private ResponseEntity<?> read(Object data) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiResponse.success(data, MDC.get(TraceIds.MDC_KEY)));
    }

    private ResponseEntity<?> response(CatalogIdempotency.Result result) {
        Object body = ApiResponse.success(result.data(), MDC.get(TraceIds.MDC_KEY));
        if (result.status() >= 400) {
            var error = (Map<?, ?>) result.data();
            body = new ApiResponse<>(error.get("code").toString(), error.get("message").toString(), error.get("application"), MDC.get(TraceIds.MDC_KEY));
        }
        return ResponseEntity.status(result.status()).header("Cache-Control", "no-store")
                .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(body);
    }

    @PostMapping("/applications")
    ResponseEntity<?> create(@AuthenticationPrincipal AuthActor actor,
                             @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Create input) {
        return response(applications.create(actor, key, input));
    }

    @GetMapping("/applications")
    ResponseEntity<?> mine(@AuthenticationPrincipal AuthActor actor,
                           @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return read(applications.list(actor, false, status, page, size));
    }

    @GetMapping("/admin/applications")
    ResponseEntity<?> admin(@AuthenticationPrincipal AuthActor actor,
                            @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return read(applications.list(actor, true, status, page, size));
    }

    @GetMapping("/applications/{id}")
    ResponseEntity<?> detail(@AuthenticationPrincipal AuthActor actor, @PathVariable long id) {
        return read(applications.detail(actor, id));
    }

    @PostMapping("/applications/{id}/cancel")
    ResponseEntity<?> cancel(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                             @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Action input) {
        return response(applications.act(actor, key, id, input, "CANCEL"));
    }

    @PostMapping("/admin/applications/{id}/approve")
    ResponseEntity<?> approve(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                              @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Action input) {
        return response(applications.act(actor, key, id, input, "APPROVE"));
    }

    @PostMapping("/admin/applications/{id}/reject")
    ResponseEntity<?> reject(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                             @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Action input) {
        return response(applications.act(actor, key, id, input, "REJECT"));
    }

    @PostMapping("/admin/applications/{id}/resolve-conflict")
    ResponseEntity<?> resolve(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                              @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Resolve input) {
        return response(applications.resolve(actor, key, id, input));
    }
}
