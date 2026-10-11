package org.shen.graduationproject.m1;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.Map;

import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.contracts.TraceIds;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("m1")
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class M1BookingController {
    private final IdentityClient identity;

    @GetMapping("/internal/m1/status")
    ApiResponse<Map<String, Object>> status() {
        var result = identity.status();
        return ApiResponse.success(Map.of("service", "booking-service", "dataMode", "DEMO",
                "identity", result.data(), "identityTraceId", result.traceId()), MDC.get(TraceIds.MDC_KEY));
    }

    @GetMapping("/internal/m1/resources/{id}")
    ResponseEntity<?> resource(@PathVariable String id) {
        if (!"demo-venue-1".equals(id)) {
            return ResponseEntity.status(404).body(ApiResponse.error(
                    "NOT_FOUND", "演示资源不存在", MDC.get(TraceIds.MDC_KEY)));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of("resourceId", "demo-venue-1",
                "name", "技术验证演示场地", "capacity", 20, "dataMode", "DEMO",
                "availability", "UNKNOWN"), MDC.get(TraceIds.MDC_KEY)));
    }
}
