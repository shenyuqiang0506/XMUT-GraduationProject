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

@RestController
@Profile("business")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AuthController {
    private final AuthenticationService authentication;
    private final LoginThrottle throttle;

    private ResponseEntity<?> response(Object data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data, MDC.get(TraceIds.MDC_KEY)));
    }

    @PostMapping("/auth/login")
    ResponseEntity<?> login(@Valid @RequestBody AuthCommands.Login request, jakarta.servlet.http.HttpServletRequest http) {
        throttle.check(request.username(), http.getRemoteAddr());
        return response(authentication.login(request));
    }

    @PostMapping("/auth/refresh")
    ResponseEntity<?> refresh(@Valid @RequestBody AuthCommands.Refresh request) {
        return response(authentication.refresh(request));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<?> logout(@AuthenticationPrincipal AuthActor actor) {
        authentication.logout(actor);
        return response(Map.of("loggedOut", true));
    }

    @GetMapping("/users/me")
    ResponseEntity<?> me(@AuthenticationPrincipal AuthActor actor) {
        return response(actor);
    }

    @GetMapping("/internal/auth/session")
    ResponseEntity<?> session(@AuthenticationPrincipal AuthActor actor) {
        return response(actor);
    }
}
