package org.shen.identity.controller;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import jakarta.validation.Valid;
import org.shen.identity.application.UserAdministration;
import org.shen.identity.domain.AuthCommands;
import org.shen.platform.contracts.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("business & !m1")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class UsersController {
    private final UserAdministration users;
    private final org.shen.identity.infrastructure.IdentityRepository repo;

    @GetMapping("/internal/auth/users/{id}")
    ApiResponse<?> internalUser(@PathVariable long id) {
        var user = repo.user(id, false);
        if (user == null) throw new org.shen.platform.support.BusinessException(404, "NOT_FOUND", "账号不存在");
        return ApiResponse.success(java.util.Map.of("userId", Long.toString(id), "status", user.status(), "roles", repo.roles(id)), MDC.get(TraceIds.MDC_KEY));
    }

    private ResponseEntity<?> response(UserAdministration.Mutation result) {
        return ResponseEntity.status(result.status()).header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .header("Cache-Control", "no-store").body(ApiResponse.success(result.data(), MDC.get(TraceIds.MDC_KEY)));
    }

    @PostMapping("/users")
    ResponseEntity<?> create(@AuthenticationPrincipal AuthActor actor,
                             @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AuthCommands.CreateUser request) {
        return response(users.create(actor, key, request));
    }

    @GetMapping("/users")
    ApiResponse<?> list(@AuthenticationPrincipal AuthActor actor,
                        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                        @RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String status) {
        return ApiResponse.success(users.list(actor, page, size, username, status), MDC.get(TraceIds.MDC_KEY));
    }

    @PatchMapping("/users/{id}/status")
    ResponseEntity<?> status(@AuthenticationPrincipal AuthActor actor,
                             @RequestHeader("Idempotency-Key") String key, @PathVariable long id, @Valid @RequestBody AuthCommands.Status request) {
        return response(users.status(actor, key, id, request));
    }

    @PutMapping("/users/{id}/roles")
    ResponseEntity<?> roles(@AuthenticationPrincipal AuthActor actor,
                            @RequestHeader("Idempotency-Key") String key, @PathVariable long id, @Valid @RequestBody AuthCommands.Roles request) {
        return response(users.roles(actor, key, id, request));
    }
}
