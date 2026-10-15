package org.shen.notification;

import lombok.RequiredArgsConstructor;
import org.shen.platform.contracts.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 运维接口只返回死信摘要，原载荷和 broker 凭据不进入 Web；每次重放需填写检查理由。
 */
@RestController
@Profile("business")
@RequiredArgsConstructor
class DeadLettersController {
    private final DeadLetterOperations operations;

    @GetMapping("/admin/notifications/dead-letters")
    ResponseEntity<?> peek(@AuthenticationPrincipal AuthActor actor) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(operations.peek(actor), MDC.get(TraceIds.MDC_KEY)));
    }

    @PostMapping("/admin/notifications/dead-letters/{eventId}/replay")
    ResponseEntity<?> replay(@AuthenticationPrincipal AuthActor actor, @PathVariable String eventId, @RequestHeader("Idempotency-Key") String key, @RequestBody DeadLetterOperations.Replay input) {
        var result = operations.replay(actor, key, eventId, input);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(ApiResponse.success(result.data(), MDC.get(TraceIds.MDC_KEY)));
    }
}
