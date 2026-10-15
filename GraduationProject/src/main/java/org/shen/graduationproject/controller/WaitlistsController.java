package org.shen.graduationproject.controller;

import java.util.Map;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.application.Waitlists;
import org.shen.graduationproject.domain.ApplicationCommands.*;
import org.shen.graduationproject.infrastructure.CatalogIdempotency;
import org.shen.platform.contracts.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 候补接口不接受 groupId、申请人、状态或客户端 TTL；身份、组合及期限由服务端确定。
 */
@RestController
@Profile("business")
@RequiredArgsConstructor
class WaitlistsController {
    private final Waitlists waitlists;

    private ResponseEntity<?> read(Object body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(body, MDC.get(TraceIds.MDC_KEY)));
    }

    private ResponseEntity<?> mutation(CatalogIdempotency.Result result) {
        Object body = ApiResponse.success(result.data(), MDC.get(TraceIds.MDC_KEY));
        if (result.status() >= 400) {
            var error = (Map<?, ?>) result.data();
            body = new ApiResponse<>(error.get("code").toString(), error.get("message").toString(), error.get("application"), MDC.get(TraceIds.MDC_KEY));
        }
        return ResponseEntity.status(result.status()).header("Cache-Control", "no-store").header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(body);
    }

    @PostMapping("/waitlists")
    ResponseEntity<?> create(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Create input) {
        return mutation(waitlists.create(actor, key, input));
    }

    @GetMapping("/waitlists")
    ResponseEntity<?> list(@AuthenticationPrincipal AuthActor actor, @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return read(waitlists.list(actor, status, page, size));
    }

    @GetMapping("/waitlists/{id}")
    ResponseEntity<?> detail(@AuthenticationPrincipal AuthActor actor, @PathVariable long id) {
        return read(waitlists.detail(actor, id));
    }

    @PostMapping("/waitlists/{id}/confirm")
    ResponseEntity<?> confirm(@AuthenticationPrincipal AuthActor actor, @PathVariable long id, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Action input) {
        return mutation(waitlists.confirm(actor, key, id, input));
    }

    @PostMapping("/waitlists/{id}/cancel")
    ResponseEntity<?> cancel(@AuthenticationPrincipal AuthActor actor, @PathVariable long id, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Action input) {
        return mutation(waitlists.cancel(actor, key, id, input));
    }
}
