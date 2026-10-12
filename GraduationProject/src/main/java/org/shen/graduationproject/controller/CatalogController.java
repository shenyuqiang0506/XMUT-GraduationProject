package org.shen.graduationproject.controller;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.Map;

import jakarta.validation.Valid;
import org.shen.graduationproject.application.*;
import org.shen.graduationproject.domain.CatalogCommands;
import org.shen.graduationproject.infrastructure.CatalogIdempotency;
import org.shen.platform.contracts.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 资源目录、审批组与文件导入的 HTTP 适配层。
 * 负责取得认证操作者、校验请求体、转交幂等键并包装响应；业务规则由应用服务实现。
 * 管理范围必须在服务端检查，不能以 Web 按钮是否可见作为权限依据。
 */
@RestController
@Profile("business")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class CatalogController {
    private final ResourceCatalog resources;
    private final CatalogImports imports;

    private ApiResponse<?> ok(Object data) {
        return ApiResponse.success(data, MDC.get(TraceIds.MDC_KEY));
    }

    private ResponseEntity<?> result(CatalogIdempotency.Result outcome) {
        return ResponseEntity.status(outcome.status()).header("Idempotency-Replayed", Boolean.toString(outcome.replayed())).body(ok(outcome.data()));
    }

    /**
     * 分页读取当前操作者可见的资源，筛选范围由服务端授权与白名单共同限制。
     */
    @GetMapping("/resources")
    ApiResponse<?> resources(@AuthenticationPrincipal AuthActor actor, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size, @RequestParam Map<String, String> filters) {
        return ok(resources.list(actor, page, size, filters));
    }

    /**
     * 读取单个资源详情；不存在与不可见使用相同业务错误，避免越界探测。
     */
    @GetMapping("/resources/{id}")
    ApiResponse<?> detail(@AuthenticationPrincipal AuthActor actor, @PathVariable long id) {
        return ok(resources.detail(actor, id));
    }

    /**
     * 创建资源时转交完整输入与幂等键，由应用层检查审批组及来源开放约束。
     */
    @PostMapping("/admin/resources")
    ResponseEntity<?> create(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CatalogCommands.Resource input) {
        return result(resources.create(actor, key, input));
    }

    /**
     * 局部更新使用 Map 接收白名单字段，应用层负责拒绝未知字段并核对 expectedVersion。
     */
    @PatchMapping("/admin/resources/{id}")
    ResponseEntity<?> patch(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @PathVariable long id, @RequestBody Map<String, Object> input) {
        return result(resources.patch(actor, key, id, input));
    }

    /**
     * 查询当前管理员可见的审批组及成员。
     */
    @GetMapping("/admin/approval-groups")
    ApiResponse<?> groups(@AuthenticationPrincipal AuthActor actor) {
        return ok(resources.groups(actor));
    }

    /**
     * 系统管理员创建审批组，重复提交由幂等层处理。
     */
    @PostMapping("/admin/approval-groups")
    ResponseEntity<?> group(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CatalogCommands.Group input) {
        return result(resources.createGroup(actor, key, input));
    }

    /**
     * 整体替换审批组成员；成员角色需经身份服务核实。
     */
    @PutMapping("/admin/approval-groups/{id}/members")
    ResponseEntity<?> members(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @PathVariable long id, @Valid @RequestBody CatalogCommands.Members input) {
        return result(resources.members(actor, key, id, input, false));
    }

    /**
     * 整体替换资源直属管理员，与审批组成员授权并存。
     */
    @PutMapping("/admin/resources/{id}/managers")
    ResponseEntity<?> managers(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @PathVariable long id, @Valid @RequestBody CatalogCommands.Members input) {
        return result(resources.members(actor, key, id, input, true));
    }

    /**
     * 接收 multipart 文件与 JSON 映射、上下文，生成待确认批次；预览不等同于业务写入成功。
     */
    @PostMapping("/admin/imports/preview")
    ResponseEntity<?> preview(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @RequestParam MultipartFile file,
                              @RequestParam String importType, @RequestParam String sourceSystem, @RequestParam String dataMode, @RequestParam(defaultValue = "{}") String fieldMapping, @RequestParam(defaultValue = "{}") String queryContext) {
        return result(imports.preview(actor, key, file, importType, sourceSystem, dataMode, fieldMapping, queryContext));
    }

    /**
     * 读取已保存的预览摘要和行级错误，返回内容不包含本机存储路径。
     */
    @GetMapping("/admin/imports/{id}")
    ApiResponse<?> batch(@AuthenticationPrincipal AuthActor actor, @PathVariable long id) {
        return ok(imports.get(actor, id));
    }

    /**
     * 确认服务端保存的批次，文件摘要、行数据与当前授权都会重新检查。
     */
    @PostMapping("/admin/imports/{id}/commit")
    ResponseEntity<?> commit(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @PathVariable long id) {
        return result(imports.commit(actor, key, id));
    }
}
