package org.shen.graduationproject.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.application.OutboxAdministration;
import org.shen.graduationproject.domain.ApplicationCommands.Action;
import org.shen.platform.contracts.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 仅系统管理员可查看投递摘要及人工重试；响应不包含事件载荷和他人的个人资料。
 */
@RestController
@Profile("business")
@RequiredArgsConstructor
class OutboxController {
    private final OutboxAdministration events;

    @GetMapping("/admin/outbox")
    ResponseEntity<?> list(@AuthenticationPrincipal AuthActor actor, @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(events.list(actor, status, page, size), MDC.get(TraceIds.MDC_KEY)));
    }

    @PostMapping("/admin/outbox/{id}/retry")
    ResponseEntity<?> retry(@AuthenticationPrincipal AuthActor actor, @PathVariable long id, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Action input) {
        var result = events.retry(actor, key, id, input);
        return ResponseEntity.status(result.status()).header("Cache-Control", "no-store").header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(ApiResponse.success(result.data(), MDC.get(TraceIds.MDC_KEY)));
    }
}
