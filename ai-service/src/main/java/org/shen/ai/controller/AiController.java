package org.shen.ai.controller;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.shen.ai.application.AiConversations;
import org.shen.ai.domain.AiCommands;
import org.shen.ai.infrastructure.AiRepository;
import org.shen.platform.contracts.*;
import org.shen.platform.support.BusinessException;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** AI 入口仅管理本人对话与只读方案；所有响应 no-store，令牌不进入请求体或持久化模型。 */
@RestController @Profile("business") @RequiredArgsConstructor(access=AccessLevel.PACKAGE)
class AiController {
    private final AiRepository repo;
    private final AiConversations conversations;
    private ResponseEntity<?> ok(int status,Object data,boolean replayed) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").header("Idempotency-Replayed",Boolean.toString(replayed))
            .body(ApiResponse.success(data,MDC.get(TraceIds.MDC_KEY)));
    }
    private String token(Authentication authentication) {
        if(authentication.getCredentials() instanceof String token)return token;
        throw new BusinessException(401,"UNAUTHORIZED","需要有效访问令牌");
    }
    @PostMapping("/ai/conversations")
    ResponseEntity<?> create(@AuthenticationPrincipal AuthActor actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody AiCommands.Create input) {
        var created=repo.create(actor,key,input.dataMode());return ok(201,created.data(),created.replayed());
    }
    @GetMapping("/ai/conversations")
    ResponseEntity<?> list(@AuthenticationPrincipal AuthActor actor){return ok(200,repo.list(actor),false);}
    @PostMapping("/ai/conversations/{id}/messages")
    ResponseEntity<?> send(@AuthenticationPrincipal AuthActor actor,Authentication authentication,@PathVariable long id,@Valid @RequestBody AiCommands.Send input) {
        var result=conversations.send(actor,id,input,token(authentication),TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY)));
        return ok(200,result.data(),result.replayed());
    }
    @GetMapping("/ai/conversations/{id}/messages")
    ResponseEntity<?> history(@AuthenticationPrincipal AuthActor actor,Authentication authentication,@PathVariable long id) {
        return ok(200,conversations.history(actor,id,token(authentication),TraceIds.acceptOrCreate(MDC.get(TraceIds.MDC_KEY))),false);
    }
    @PostMapping("/ai/conversations/{id}/close")
    ResponseEntity<?> close(@AuthenticationPrincipal AuthActor actor,@PathVariable long id,@Valid @RequestBody AiCommands.Close input) {
        return ok(200,repo.close(actor,id,input.expectedConditionVersion()),false);
    }
}
