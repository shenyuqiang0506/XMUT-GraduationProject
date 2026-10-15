package org.shen.notification;

import lombok.RequiredArgsConstructor;
import org.shen.platform.contracts.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 本人通知只按实时认证身份读取；已读是自然幂等动作，重复请求保留原 readAt。
 */
@RestController
@Profile("business")
@RequiredArgsConstructor
class NotificationsController {
    private final NotificationStore notifications;

    private ResponseEntity<?> response(Object data) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(data, MDC.get(TraceIds.MDC_KEY)));
    }

    @GetMapping("/notifications")
    ResponseEntity<?> list(@AuthenticationPrincipal AuthActor actor, @RequestParam(defaultValue = "false") boolean unreadOnly, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return response(notifications.list(actor, unreadOnly, page, size));
    }

    @PostMapping("/notifications/{id}/read")
    ResponseEntity<?> read(@AuthenticationPrincipal AuthActor actor, @PathVariable long id) {
        return response(notifications.read(actor, id));
    }
}
