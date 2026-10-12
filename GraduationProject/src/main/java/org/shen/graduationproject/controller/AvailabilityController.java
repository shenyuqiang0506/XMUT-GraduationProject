package org.shen.graduationproject.controller;

import java.time.OffsetDateTime;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.shen.graduationproject.application.*;
import org.shen.graduationproject.domain.AvailabilityCommands.*;
import org.shen.graduationproject.infrastructure.CatalogIdempotency;
import org.shen.platform.contracts.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端维护和普通用户查询共用服务端权限、时间及来源校验。
 */
@RestController
@Profile("business")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AvailabilityController {
    private final AvailabilityQueries queries;
    private final AvailabilityMaintenance maintenance;
    private final BookingPolicies policies;

    private ApiResponse<?> ok(Object data) {
        return ApiResponse.success(data, MDC.get(TraceIds.MDC_KEY));
    }

    private ResponseEntity<?> result(CatalogIdempotency.Result outcome) {
        return ResponseEntity.status(outcome.status()).header("Idempotency-Replayed", Boolean.toString(outcome.replayed())).body(ok(outcome.data()));
    }

    @GetMapping("/resources/{id}/calendar")
    ApiResponse<?> calendar(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ok(queries.calendar(actor, id, from, to));
    }

    @PostMapping("/availability/search")
    ApiResponse<?> search(@AuthenticationPrincipal AuthActor actor, @Valid @RequestBody Search input) {
        return ok(queries.search(actor, input));
    }

    @PutMapping("/admin/resources/{id}/opening-rules")
    ResponseEntity<?> opening(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                              @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody OpeningRules input) {
        return result(maintenance.opening(actor, key, id, input));
    }

    @GetMapping("/admin/resources/{id}/external-occupancies")
    ApiResponse<?> occupancies(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                               @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(maintenance.list(actor, id, page, size));
    }

    @PostMapping("/admin/resources/{id}/external-occupancies")
    ResponseEntity<?> occupancy(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                                @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Occupancy input) {
        return result(maintenance.create(actor, key, id, input));
    }

    @PatchMapping("/admin/external-occupancies/{id}")
    ResponseEntity<?> patch(@AuthenticationPrincipal AuthActor actor, @PathVariable long id,
                            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody OccupancyUpdate input) {
        return result(maintenance.patch(actor, key, id, input));
    }

    @GetMapping("/admin/policies/{scope}")
    ApiResponse<?> policy(@AuthenticationPrincipal AuthActor actor, @PathVariable String scope) {
        return ok(policies.get(actor, scope));
    }

    @PostMapping("/admin/policies/{scope}/versions")
    ResponseEntity<?> policyVersion(@AuthenticationPrincipal AuthActor actor, @PathVariable String scope,
                                    @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PolicyVersion input) {
        return result(policies.create(actor, key, scope, input));
    }
}
