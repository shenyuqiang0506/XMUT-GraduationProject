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

@RestController
@Profile("business & !m1")
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

    @GetMapping("/resources")
    ApiResponse<?> resources(@AuthenticationPrincipal AuthActor actor, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size, @RequestParam Map<String, String> filters) {
        return ok(resources.list(actor, page, size, filters));
    }

    @GetMapping("/resources/{id}")
    ApiResponse<?> detail(@AuthenticationPrincipal AuthActor actor, @PathVariable long id) {
        return ok(resources.detail(actor, id));
    }

    @PostMapping("/admin/resources")
    ResponseEntity<?> create(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CatalogCommands.Resource input) {
        return result(resources.create(actor, key, input));
    }

    @PatchMapping("/admin/resources/{id}")
    ResponseEntity<?> patch(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @PathVariable long id, @RequestBody Map<String, Object> input) {
        return result(resources.patch(actor, key, id, input));
    }

    @GetMapping("/admin/approval-groups")
    ApiResponse<?> groups(@AuthenticationPrincipal AuthActor actor) {
        return ok(resources.groups(actor));
    }

    @PostMapping("/admin/approval-groups")
    ResponseEntity<?> group(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CatalogCommands.Group input) {
        return result(resources.createGroup(actor, key, input));
    }

    @PutMapping("/admin/approval-groups/{id}/members")
    ResponseEntity<?> members(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @PathVariable long id, @Valid @RequestBody CatalogCommands.Members input) {
        return result(resources.members(actor, key, id, input, false));
    }

    @PutMapping("/admin/resources/{id}/managers")
    ResponseEntity<?> managers(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @PathVariable long id, @Valid @RequestBody CatalogCommands.Members input) {
        return result(resources.members(actor, key, id, input, true));
    }

    @PostMapping("/admin/imports/preview")
    ResponseEntity<?> preview(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @RequestParam MultipartFile file,
                              @RequestParam String importType, @RequestParam String sourceSystem, @RequestParam String dataMode, @RequestParam(defaultValue = "{}") String fieldMapping, @RequestParam(defaultValue = "{}") String queryContext) {
        return result(imports.preview(actor, key, file, importType, sourceSystem, dataMode, fieldMapping, queryContext));
    }

    @GetMapping("/admin/imports/{id}")
    ApiResponse<?> batch(@AuthenticationPrincipal AuthActor actor, @PathVariable long id) {
        return ok(imports.get(actor, id));
    }

    @PostMapping("/admin/imports/{id}/commit")
    ResponseEntity<?> commit(@AuthenticationPrincipal AuthActor actor, @RequestHeader("Idempotency-Key") String key, @PathVariable long id) {
        return result(imports.commit(actor, key, id));
    }
}
