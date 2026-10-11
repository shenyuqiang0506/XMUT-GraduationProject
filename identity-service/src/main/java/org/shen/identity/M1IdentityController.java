package org.shen.identity;

import java.util.Map;
import org.shen.platform.contracts.ApiResponse;
import org.shen.platform.contracts.TraceIds;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("m1")
class M1IdentityController {
    @GetMapping("/internal/m1/status")
    ApiResponse<Map<String, String>> status() {
        return ApiResponse.success(Map.of("service", "identity-service", "dataMode", "DEMO"),
                MDC.get(TraceIds.MDC_KEY));
    }
}
