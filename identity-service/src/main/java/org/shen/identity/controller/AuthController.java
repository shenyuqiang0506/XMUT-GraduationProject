package org.shen.identity.controller;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.Map;

import jakarta.validation.Valid;
import org.shen.identity.application.AuthenticationService;
import org.shen.identity.domain.AuthCommands;
import org.shen.identity.infrastructure.LoginThrottle;
import org.shen.platform.contracts.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 身份认证 HTTP 入口，仅负责参数校验、限流和响应转换。
 * 请求主体由认证过滤器注入；登录和刷新响应禁止缓存，降低凭据被代理缓存的风险。
 */
@RestController
@Profile("business")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AuthController {
    private final AuthenticationService authentication;
    private final LoginThrottle throttle;

    private ResponseEntity<?> response(Object data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data, MDC.get(TraceIds.MDC_KEY)));
    }

    /**
     * 先执行来源和账号限流，再验证密码；使用直连地址作为当前限流来源。
     */
    @PostMapping("/auth/login")
    ResponseEntity<?> login(@Valid @RequestBody AuthCommands.Login request, jakarta.servlet.http.HttpServletRequest http) {
        throttle.check(request.username(), http.getRemoteAddr());
        return response(authentication.login(request));
    }

    /**
     * 刷新凭据与设备标识一并校验，成功后返回轮换的新凭据。
     */
    @PostMapping("/auth/refresh")
    ResponseEntity<?> refresh(@Valid @RequestBody AuthCommands.Refresh request) {
        return response(authentication.refresh(request));
    }

    /**
     * 撤销当前认证会话，客户端仍应清理自己的内存令牌。
     */
    @PostMapping("/auth/logout")
    ResponseEntity<?> logout(@AuthenticationPrincipal AuthActor actor) {
        authentication.logout(actor);
        return response(Map.of("loggedOut", true));
    }

    /**
     * 返回服务端实时核验的身份，供前端显示账号及决定导航入口。
     */
    @GetMapping("/users/me")
    ResponseEntity<?> me(@AuthenticationPrincipal AuthActor actor) {
        return response(actor);
    }

    /**
     * 内部服务复核身份使用的入口；返回值已通过本服务的实时会话过滤器。
     */
    @GetMapping("/internal/auth/session")
    ResponseEntity<?> session(@AuthenticationPrincipal AuthActor actor) {
        return response(actor);
    }
}
