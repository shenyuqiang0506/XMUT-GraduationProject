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

/**
 * 账号管理及跨服务用户查询入口。
 * 外部账号管理需系统管理员权限；内部查询仅返回状态和角色，不返回密码摘要。
 * 写接口传递幂等键和版本号，由应用层在事务中执行最终校验。
 */
@RestController
@Profile("business")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class UsersController {
    private final UserAdministration users;
    private final org.shen.identity.infrastructure.IdentityRepository repo;

    /** 后台候补检查使用专用服务签名链，响应只含当前资格，不含会话、密码或个人资料。 */
    @GetMapping("/internal/booking/applicants/{id}")
    ResponseEntity<?> bookingApplicant(@PathVariable long id) {
        var user=repo.user(id,false);
        if(user==null)throw new org.shen.platform.support.BusinessException(404,"NOT_FOUND","账号不存在");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.success(
                java.util.Map.of("userId",Long.toString(id),"status",user.status(),"roles",repo.roles(id)),MDC.get(TraceIds.MDC_KEY)));
    }

    /**
     * 向有权管理的内部调用方返回目标用户状态和角色，用于校验管理员资格。
     */
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

    /**
     * 预留给人员名单及未来 Excel/CSV 适配器的统一 JSON 预览入口，不持久化密码。
     * 与其他 /users 管理接口一致，仅系统管理员可调用。
     */
    @PostMapping("/users/imports/preview")
    ResponseEntity<?> previewPersonnel(@AuthenticationPrincipal AuthActor actor,
                                       @Valid @RequestBody AuthCommands.PersonnelImport request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ApiResponse.success(users.previewPersonnel(actor, request), MDC.get(TraceIds.MDC_KEY)));
    }

    /**
     * 确认开户必须携带幂等键；相同名单和密码重试回放结果，任一失败则整批回滚。
     */
    @PostMapping("/users/imports")
    ResponseEntity<?> importPersonnel(@AuthenticationPrincipal AuthActor actor,
                                      @RequestHeader("Idempotency-Key") String key,
                                      @Valid @RequestBody AuthCommands.PersonnelImport request) {
        return response(users.importPersonnel(actor, key, request));
    }

    /**
     * 创建账号，初始密码只作为输入，响应不会回传密码或其摘要。
     */
    @PostMapping("/users")
    ResponseEntity<?> create(@AuthenticationPrincipal AuthActor actor,
                             @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AuthCommands.CreateUser request) {
        return response(users.create(actor, key, request));
    }

    /**
     * 按账号和状态分页查询，最终系统管理员权限由应用层再次检查。
     */
    @GetMapping("/users")
    ApiResponse<?> list(@AuthenticationPrincipal AuthActor actor,
                        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                        @RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String status) {
        return ApiResponse.success(users.list(actor, page, size, username, status), MDC.get(TraceIds.MDC_KEY));
    }

    /**
     * 带身份预期版本启停账号，成功变更会撤销旧会话。
     */
    @PatchMapping("/users/{id}/status")
    ResponseEntity<?> status(@AuthenticationPrincipal AuthActor actor,
                             @RequestHeader("Idempotency-Key") String key, @PathVariable long id, @Valid @RequestBody AuthCommands.Status request) {
        return response(users.status(actor, key, id, request));
    }

    /**
     * 整体替换角色集合并撤销旧会话，服务端保护最后一个有效系统管理员。
     */
    @PutMapping("/users/{id}/roles")
    ResponseEntity<?> roles(@AuthenticationPrincipal AuthActor actor,
                            @RequestHeader("Idempotency-Key") String key, @PathVariable long id, @Valid @RequestBody AuthCommands.Roles request) {
        return response(users.roles(actor, key, id, request));
    }
}
